package com.greensamcli.cli;

/**
 * 终端输出渲染器接口。
 *
 * <p>将 Agent 运行过程中的每一步（用户输入、思考、AI 回复、工具调用、token 消耗、
 * 错误）以带 emoji 前缀的独立样式输出到终端，让 Agent 链路的每一步都可见。
 * 默认实现 {@link TerminalRenderer} 为每类事件分配 emoji 与 ANSI 颜色。</p>
 *
 * <p>设计为接口而非直接打印，便于：</p>
 * <ul>
 *   <li>测试时替换为 Mock 实现（不依赖真实终端）</li>
 *   <li>未来支持其他输出方式（如 Web UI、IDE 插件）</li>
 * </ul>
 */
public interface CliRenderer {

    /**
     * 显示用户输入（🥷🏻，完整回显本轮对话的角色起点）
     */
    void displayUser(String text);

    /**
     * 显示系统消息（💡 灰色，如启动提示、清除历史等）
     */
    void displaySystem(String text);

    /**
     * 显示 AI 完整回复（🤖 青色，同步模式一次性输出）
     */
    void displayAssistant(String text);

    /**
     * 显示 AI 文本增量（🤖 青色，流式模式打字机效果）。
     * 与 {@link #displayReasoningDelta} 配合：若思考流先出现，首个文本增量
     * 负责结束思考行、开启回答行。
     */
    void displayAssistantDelta(String delta);

    /**
     * 结束流式 AI 回复块（收尾换行、重置颜色与内部状态）。
     * 流式结束后必须调用一次，否则后续输出可能延续未关闭的样式。
     */
    void displayAssistantEnd();

    /**
     * 显示推理（思考过程）增量（💭 灰色斜体，流式模式）
     */
    void displayReasoningDelta(String delta);

    /**
     * 显示工具调用信息（统一 🛠️ 黄色，独占一行，
     * 如 "  🛠️ 调用 read_file({...})"）
     */
    void displayToolCall(String toolName, String arguments);

    /**
     * 显示工具执行结果（✅ 绿色，单行摘要：换行折叠为 ⏎，超长截断）
     */
    void displayToolResult(String toolName, String result);

    /**
     * 显示错误消息（❌ 红色，如 API 错误、工具执行失败等）
     */
    void displayError(String message);

    /**
     * 显示 token 用量（📊：本轮输入/输出 + 会话累计输入/输出）
     *
     * @param roundPromptTokens     本轮所有 LLM 调用的输入 token 合计
     * @param roundCompletionTokens 本轮所有 LLM 调用的输出 token 合计
     * @param totalPromptTokens     会话累计输入 token
     * @param totalCompletionTokens 会话累计输出 token
     */
    void displayTokenUsage(int roundPromptTokens, int roundCompletionTokens,
                           int totalPromptTokens, int totalCompletionTokens);
}
