package com.greensamcli.agent;

import com.greensamcli.model.ChatResponse;
import com.greensamcli.model.ToolCall;

/**
 * 工具调用事件监听器——用于在终端显示工具执行的实时状态。
 *
 * <p>AgentLoop 在执行工具时会触发此接口的方法，
 * CLI 层（Repl）通过实现此接口在终端显示工具调用过程，
 * 让用户能看到 Agent 正在做什么。</p>
 *
 * <p>事件时序：</p>
 * <pre>
 * LLM 返回 tool_call
 *     │
 *     ▼
 * onToolCallStarted(call)         ← 终端显示：[tool] read_file({"path":"/tmp/test.txt"})
 *     │
 *     ▼
 * 执行工具 ...
 *     │
 *     ├── 成功 → onToolCallCompleted(name, result)
 *     │          ← 终端显示：[result] read_file: 文件内容...
 *     │
 *     └── 失败 → onToolCallFailed(name, error)
 *                ← 终端显示：[error] read_file: File not found
 * </pre>
 *
 * <p>设计为接口而非直接依赖终端，使得 AgentLoop 可以脱离 CLI 进行测试。
 * 测试时可以传入一个记录事件的 listener，验证工具是否被正确调用。</p>
 */
public interface ToolCallListener {

    /**
     * 工具开始执行时触发（LLM 刚发起了 tool_call，工具即将执行）
     */
    void onToolCallStarted(ToolCall call);

    /**
     * 工具执行成功时触发，result 是工具返回的文本结果
     */
    void onToolCallCompleted(String toolName, String result);

    /**
     * 工具执行失败时触发，error 是失败原因
     */
    void onToolCallFailed(String toolName, String error);

    /**
     * 一轮对话（一次 run / runStreaming，可含多次 LLM 调用）完成时触发，
     * usage 是本轮所有 LLM 调用的 token 用量合计。
     *
     * <p>实现为 default 空方法：不关心用量的监听器无需实现。</p>
     */
    default void onRoundUsage(ChatResponse.Usage usage) {
    }
}
