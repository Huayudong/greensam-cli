package com.greensamcli.cli;

/**
 * 终端输出渲染器接口。
 *
 * <p>将不同类型的内容（系统消息、AI 回复、工具调用、错误）以不同样式输出到终端。
 * 默认实现 {@link TerminalRenderer} 使用 ANSI 颜色码区分不同类型。</p>
 *
 * <p>设计为接口而非直接打印，便于：</p>
 * <ul>
 *   <li>测试时替换为 Mock 实现（不依赖真实终端）</li>
 *   <li>未来支持其他输出方式（如 Web UI、IDE 插件）</li>
 * </ul>
 */
public interface CliRenderer {

    /**
     * 显示系统消息（灰色，如启动提示、清除历史等）
     */
    void displaySystem(String text);

    /**
     * 显示 AI 回复（青色，这是主要的对话输出）
     */
    void displayAssistant(String text);

    /**
     * 显示工具调用信息（黄色，如 "[tool] read_file({"path":"/tmp/test"})"）
     */
    void displayToolCall(String toolName, String arguments);

    /**
     * 显示工具执行结果（绿色，截断过长的结果）
     */
    void displayToolResult(String toolName, String result);

    /**
     * 显示错误消息（红色，如 API 错误、工具执行失败等）
     */
    void displayError(String message);
}
