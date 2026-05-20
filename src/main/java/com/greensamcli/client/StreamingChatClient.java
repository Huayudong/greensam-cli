package com.greensamcli.client;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ToolDefinition;

import java.util.List;

/**
 * LLM API 的流式调用接口。
 *
 * <p>基于 SSE（Server-Sent Events）协议，LLM 的响应不是一次性返回，
 * 而是通过 {@code data: {...}} 事件逐块推送。每个块只包含一小段增量内容（delta）。</p>
 *
 * <p>流式调用的好处：</p>
 * <ul>
 *   <li>用户体验：文字逐字出现（"打字机效果"），而不是等几秒后一次性显示</li>
 *   <li>首字延迟低：第一个 token 很快就能显示，不用等全部生成完</li>
 * </ul>
 *
 * <p>调用方通过 {@link StreamCallback} 接收每个增量事件。</p>
 */
public interface StreamingChatClient {

    /**
     * 以流式方式发送对话给 LLM。
     * 该方法会阻塞当前线程，直到 LLM 完成全部输出（收到 [DONE] 标记）。
     *
     * @param messages 完整的对话历史
     * @param tools    可用工具定义列表
     * @param callback 接收流式事件的回调（文本增量、工具调用增量、完成、错误）
     */
    void sendStreaming(List<ChatMessage> messages, List<ToolDefinition> tools, StreamCallback callback);
}
