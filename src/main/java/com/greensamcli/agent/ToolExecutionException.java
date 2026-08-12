package com.greensamcli.agent;

/**
 * 工具执行过程中的异常。
 *
 * <p>当某个 {@link Tool} 执行失败时（如文件不存在、权限不足等），
 * 应该抛出此异常而非 RuntimeException。AgentLoop 会捕获它，
 * 将错误信息作为工具结果回传给 LLM，让 LLM 知道出了什么问题并调整策略。</p>
 *
 * <p>例如 LLM 尝试读取不存在的文件 → ReadFileTool 抛出 ToolExecutionException("File not found") →
 * AgentLoop 把 "Error: File not found" 回传给 LLM → LLM 可能会换一个路径重试。</p>
 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
