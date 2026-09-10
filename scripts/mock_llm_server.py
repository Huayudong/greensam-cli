"""
greensam-cli 真实终端验证用的 mock OpenAI 兼容端点。

按用户输入与上下文分派脚本化行为：
1. 「帮我创建个测试文件」→ 下发 write_file 工具调用（触发审批，写 target 目录）
2. 「问我一个问题」      → 下发 ask_user 工具调用（触发 a/b/c/d 四选提问）
3. 「跑个长命令」        → 下发 execute_command 工具调用（ping 挂 20 秒）
4. 「长回复」            → 先吐几个文本增量，然后挂住连接不发 [DONE]
5. 工具结果已存在        → 按工具类型回复确认句
6. 其余                  → 原样回声；历史含「已中断」则回复中断确认句

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
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        tool_results = " ".join((m.get("content") or "") for m in messages
                                if m.get("role") == "tool")
        has_interrupt = any(m.get("role") == "tool" and "已中断" in (m.get("content") or "")
                            for m in messages)
        # 本会话是否已经下发过对应工具调用（发过则不再重复下发，走确认分支）
        has_write_call = any(m.get("role") == "assistant" and any(
            tc.get("function", {}).get("name") == "write_file"
            for tc in (m.get("tool_calls") or [])) for m in messages)
        has_ask_call = any(m.get("role") == "assistant" and any(
            tc.get("function", {}).get("name") == "ask_user"
            for tc in (m.get("tool_calls") or [])) for m in messages)
        has_exec_call = any(m.get("role") == "assistant" and any(
            tc.get("function", {}).get("name") == "execute_command"
            for tc in (m.get("tool_calls") or [])) for m in messages)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def sse(delta):
            obj = {"choices": [{"index": 0, "delta": delta}]}
            data = ("data: " + json.dumps(obj, ensure_ascii=False) + "\n\n").encode("utf-8")
            self.wfile.write(("%x\r\n" % len(data)).encode() + data + b"\r\n")
            self.wfile.flush()

        def tool_call(name, args, call_id):
            payload = json.dumps(args, ensure_ascii=False)
            sse({"tool_calls": [{"index": 0, "id": call_id, "type": "function",
                                 "function": {"name": name, "arguments": ""}}]})
            sse({"tool_calls": [{"index": 0, "function": {"arguments": payload}}]})

        if "长回复" in last_user and not has_tool_result:
            for piece in ["这是一", "段很长", "很长的", "回答，"]:
                sse({"content": piece})
                time.sleep(0.3)
            # 挂住连接，不发 [DONE]：制造「取消发生在流式 LLM 响应中」的场景
            time.sleep(60)
        elif "帮我创建个测试文件" in last_user and not has_write_call:
            tool_call("write_file", {
                "path": "target/approval-demo.txt",
                "content": "第一批内容\n第二批内容\n第三批内容\n",
            }, "call_write_1")
        elif "问我一个问题" in last_user and not has_ask_call:
            tool_call("ask_user", {
                "question": "重试机制用哪种实现方式？",
                "options": ["Spring Retry 注解方式",
                            "手写 while 循环 + 指数退避",
                            "引入 resilience4j 依赖"],
            }, "call_ask_1")
        elif "跑个长命令" in last_user and not has_exec_call:
            tool_call("execute_command", {"command": "ping -n 20 127.0.0.1 > nul"}, "call_exec_1")
        elif "用户的选择" in tool_results or "用户本次未作答" in tool_results:
            sse({"content": "收到，按你选择的方案继续。"})
        elif "已创建" in tool_results:
            sse({"content": "测试文件已创建完成（经你审批）。"})
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
