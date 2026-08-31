# -*- coding: utf-8 -*-
"""
批次②真实终端验证用的 mock OpenAI 兼容端点。

按用户输入内容分派三种脚本化行为：
1. 「跑个长命令」→ 下发 execute_command 工具调用（ping 挂 20 秒）
2. 「长回复」     → 先吐几个文本增量，然后挂住连接不发 [DONE]
3. 其余           → 若历史含「用户已中断」占位结果则回复确认句，否则原样回声

用法：python scripts/mock_llm_server.py [端口]
"""
import http.server
import json
import sys
import time


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        messages = body.get("messages", [])
        last_user = next((m["content"] for m in reversed(messages)
                          if m.get("role") == "user"), "")
        has_interrupt = any(m.get("role") == "tool" and "已中断" in (m.get("content") or "")
                            for m in messages)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def sse(delta):
            obj = {"choices": [{"index": 0, "delta": delta}]}
            data = ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")
            self.wfile.write(("%x\r\n" % len(data)).encode() + data + b"\r\n")
            self.wfile.flush()

        if "长回复" in last_user:
            for piece in ["这是一", "段很长", "很长的", "回答，"]:
                sse({"content": piece})
                time.sleep(0.3)
            # 挂住连接，不发 [DONE]：制造「取消发生在流式 LLM 响应中」的场景
            time.sleep(60)
        elif "跑个长命令" in last_user:
            args = json.dumps({"command": "ping -n 20 127.0.0.1 > nul"}, ensure_ascii=False)
            sse({"tool_calls": [{"index": 0, "id": "call_mock_1", "type": "function",
                                 "function": {"name": "execute_command", "arguments": ""}}]})
            sse({"tool_calls": [{"index": 0,
                                 "function": {"arguments": args}}]})
            sse_usage = ("data: " + json.dumps({"choices": [], "usage": {
                "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}}) + "\n\n").encode("utf-8")
            self.wfile.write(("%x\r\n" % len(sse_usage)).encode() + sse_usage + b"\r\n")
        elif has_interrupt:
            sse({"content": "上一轮你中断了命令执行，我可以看到中断占位结果。"})
        else:
            sse({"content": "收到：" + last_user})

        done = b"data: [DONE]\n\n"
        self.wfile.write(("%x\r\n" % len(done)).encode() + done + b"\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18081
    server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("mock LLM endpoint listening on http://127.0.0.1:%d/v1" % port, flush=True)
    server.serve_forever()
