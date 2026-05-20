package com.greensamcli.client;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ToolCall;

/**
 * 流式响应的事件回调接口。
 *
 * <p>在 SSE 流式传输过程中，不同阶段会触发不同的回调：</p>
 *
 * <pre>
 * LLM 开始生成
 *     │
 *     ├─→ onContentDelta("你")      // 文本增量，逐字触发
 *     ├─→ onContentDelta("好")
 *     ├─→ onContentDelta("！")
 *     │
 *     ├─→ onToolCallDelta(...)      // 工具调用增量（参数是逐步拼接的）
 *     │
 *     └─→ onComplete(fullMessage)   // 流结束，提供完整的 assistant 消息
 *
 * 如果中途出错 → onError(exception)
 * </pre>
 */
public interface StreamCallback {

    /**
     * 收到一段文本增量时触发。
     * 例如 LLM 生成 "你好！"，可能分 3 次触发：delta="你"、delta="好"、delta="！"。
     * 调用方应立即输出这段文本，实现打字机效果。
     */
    void onContentDelta(String delta);

    /**
     * 收到工具调用增量时触发。
     * 工具调用的参数是逐步拼接的（例如先收到 name，再收到部分 arguments），
     * 这里的 toolCall 是当前累计状态，不是增量。
     */
    void onToolCallDelta(ToolCall toolCall);

    /**
     * 流式传输完成时触发。
     * 参数是完整的 assistant 消息（包含全部文本内容和/或完整的 tool_calls），
     * 可以安全地加入对话历史。
     */
    void onComplete(ChatMessage fullAssistantMessage);

    /** 流式传输过程中发生错误时触发 */
    void onError(Exception e);
}
