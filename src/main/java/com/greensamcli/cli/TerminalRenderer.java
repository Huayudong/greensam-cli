package com.greensamcli.cli;

/**
 * 基于 ANSI 颜色码的终端渲染器。
 *
 * <p>使用转义序列为不同类型的内容着色，让终端输出更易读：</p>
 * <ul>
 *   <li>系统消息 — 灰色 {@code \033[90m}（次要信息）</li>
 *   <li>AI 回复 — 青色 {@code \033[36m}（主要内容，最醒目）</li>
 *   <li>工具调用 — 黄色 {@code \033[33m}（中间过程）</li>
 *   <li>工具结果 — 绿色 {@code \033[32m}（成功反馈）</li>
 *   <li>错误信息 — 红色 {@code \033[31m}（需要注意）</li>
 * </ul>
 *
 * <p>{@code \033[0m} (RESET) 在每段着色文本末尾重置颜色，
 * 防止颜色"泄漏"到后续输出。</p>
 */
public class TerminalRenderer implements CliRenderer {

    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String GRAY = "\033[90m";

    @Override
    public void displaySystem(String text) {
        System.out.println(GRAY + "[system] " + text + RESET);
    }

    @Override
    public void displayAssistant(String text) {
        // 前后各空一行，让 AI 回复在视觉上与工具调用和用户输入分隔开
        System.out.println();
        System.out.println(CYAN + text + RESET);
        System.out.println();
    }

    @Override
    public void displayToolCall(String toolName, String arguments) {
        System.out.println(YELLOW + "  [tool] " + toolName + "(" + arguments + ")" + RESET);
    }

    @Override
    public void displayToolResult(String toolName, String result) {
        // 截断过长的结果，避免终端被刷屏
        String truncated = result.length() > 200
                ? result.substring(0, 200) + "..."
                : result;
        // 将换行替换为 \n 字面量，让结果保持在一行内
        System.out.println(GREEN + "  [result] " + toolName + ": " + truncated.replace("\n", "\\n") + RESET);
    }

    @Override
    public void displayError(String message) {
        System.out.println(RED + "[error] " + message + RESET);
    }
}
