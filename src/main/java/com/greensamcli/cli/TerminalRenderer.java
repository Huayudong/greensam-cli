package com.greensamcli.cli;

/**
 * 基于 emoji + ANSI 颜色码的终端渲染器。
 *
 * <p><b>emoji 语义</b>：一个 emoji 代表系统的一类反应，每类事件独占一行（块）：</p>
 *
 * <ul>
 *   <li>🥷🏻 用户输入 — 默认色</li>
 *   <li>🤖 AI 回答 — 青色 {@code \033[36m}（主要内容；流式时为一个可跨多行的段落块）</li>
 *   <li>💭 思考过程 — 灰色斜体 {@code \033[90;3m}（推理型模型的 reasoning 输出）</li>
 *   <li>🛠️ 工具调用 — 黄色 {@code \033[33m}（所有工具统一使用，不按工具类型分派）</li>
 *   <li>✅ 工具结果 — 绿色 {@code \033[32m}（单行摘要，换行折叠为 ⏎，不输出字面 \n）</li>
 *   <li>📊 token 消耗 — 默认色（本轮与会话累计的输入/输出）</li>
 *   <li>❌ 错误 — 红色 {@code \033[31m}</li>
 *   <li>💡 系统消息 — 灰色 {@code \033[90m}</li>
 * </ul>
 *
 * <p><b>行纪律</b>：流式块（💭/🤖）与独立行事件（🛠️/✅/❌）互不粘连——
 * 独立行事件打印前先收掉未关闭的流式块（补 RESET + 换行）；
 * 思考块开启前先收掉未关闭的回答块；回答块开启时剥掉模型输出的前导换行，
 * 避免块首悬挂空行。流结束后由 {@link #displayAssistantEnd()} 收尾并复位。</p>
 *
 * <p>REPL 为单线程驱动流式回调，无需加锁。</p>
 */
public class TerminalRenderer implements CliRenderer {

    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String GRAY = "\033[90m";
    private static final String GRAY_ITALIC = "\033[90;3m";

    /**
     * emoji 前缀常量（与 AGENTS.md 全中文化约定一致：emoji 属于用户可见文案）
     */
    private static final String EMOJI_USER = "🥷🏻";
    private static final String EMOJI_ASSISTANT = "🤖";
    private static final String EMOJI_THINKING = "💭";
    private static final String EMOJI_TOOL = "🛠️";
    private static final String EMOJI_RESULT = "✅";
    private static final String EMOJI_USAGE = "📊";
    private static final String EMOJI_ERROR = "❌";
    private static final String EMOJI_SYSTEM = "💡";

    /**
     * 工具调用参数回显截断长度，防止超长参数刷屏
     */
    private static final int MAX_ARGS_CHARS = 120;

    /**
     * 工具结果摘要截断长度（结果全文已在对话历史中，终端只留单行摘要）
     */
    private static final int MAX_RESULT_CHARS = 200;

    /**
     * 流式状态：思考行（💭）是否已开启
     */
    private boolean reasoningActive;
    /**
     * 流式状态：回答行（🤖 + 青色）是否已开启
     */
    private boolean assistantOpen;

    @Override
    public void displayUser(String text) {
        System.out.println(EMOJI_USER + " " + text);
    }

    @Override
    public void displaySystem(String text) {
        System.out.println(GRAY + EMOJI_SYSTEM + " " + text + RESET);
    }

    @Override
    public void displayAssistant(String text) {
        System.out.println();
        System.out.println(EMOJI_ASSISTANT + " " + CYAN + text + RESET);
        System.out.println();
    }

    @Override
    public void displayAssistantDelta(String delta) {
        // 思考行还开着 → 先收掉思考行，再开启回答行
        if (reasoningActive) {
            System.out.print(RESET + "\n");
            reasoningActive = false;
        }
        if (!assistantOpen) {
            // 剥掉模型输出的前导换行（如 </think> 后的 "\n\n"），避免回答块首悬挂空行
            String first = stripLeadingNewlines(delta);
            if (first.isEmpty()) {
                return;
            }
            System.out.print(EMOJI_ASSISTANT + " " + CYAN);
            assistantOpen = true;
            System.out.print(first);
            return;
        }
        System.out.print(delta);
    }

    @Override
    public void displayReasoningDelta(String delta) {
        if (!reasoningActive) {
            // 回答块还开着 → 先收掉，保证 💭 块独占行
            closeAssistantBlock();
            System.out.print("\n" + EMOJI_THINKING + " " + GRAY_ITALIC);
            reasoningActive = true;
        }
        System.out.print(delta);
    }

    @Override
    public void displayAssistantEnd() {
        if (reasoningActive || assistantOpen) {
            System.out.print(RESET + "\n\n");
        } else {
            System.out.println();
        }
        reasoningActive = false;
        assistantOpen = false;
    }

    @Override
    public void displayToolCall(String toolName, String arguments) {
        closeStreamingBlock();
        System.out.println(YELLOW + "  " + EMOJI_TOOL + " 调用 "
                + toolName + "(" + truncateArgs(arguments) + ")" + RESET);
    }

    @Override
    public void displayToolResult(String toolName, String result) {
        closeStreamingBlock();
        System.out.println(GREEN + "  " + EMOJI_RESULT + " " + toolName + ": "
                + summarizeResult(result) + RESET);
    }

    @Override
    public void displayError(String message) {
        closeStreamingBlock();
        System.out.println(RED + EMOJI_ERROR + " " + message + RESET);
    }

    @Override
    public void displayTokenUsage(int roundPromptTokens, int roundCompletionTokens,
                                  int totalPromptTokens, int totalCompletionTokens) {
        System.out.println(EMOJI_USAGE + " 本轮 token：输入 " + roundPromptTokens
                + " / 输出 " + roundCompletionTokens
                + "　｜　会话累计：输入 " + totalPromptTokens
                + " / 输出 " + totalCompletionTokens);
    }

    /**
     * 收掉未关闭的流式块（思考块或回答块），保证后续事件从新行开始。
     * 无块开启时为空操作。
     */
    private void closeStreamingBlock() {
        if (reasoningActive || assistantOpen) {
            System.out.print(RESET + "\n");
            reasoningActive = false;
            assistantOpen = false;
        }
    }

    /**
     * 收掉未关闭的回答块（思考块开启前调用）；无回答块时为空操作
     */
    private void closeAssistantBlock() {
        if (assistantOpen) {
            System.out.print(RESET + "\n");
            assistantOpen = false;
        }
    }

    /**
     * 去掉文本开头的连续换行符
     */
    private static String stripLeadingNewlines(String text) {
        return text.replaceFirst("^[\\r\\n]+", "");
    }

    /**
     * 工具结果单行摘要：换行（含连续换行）折叠为单个 ⏎，
     * 绝不把字面 \n 打进终端；超长截断并标注原始字符数
     */
    static String summarizeResult(String result) {
        if (result == null || result.isEmpty()) {
            return "(空)";
        }
        String collapsed = result.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("\n+", "⏎");
        if (collapsed.length() <= MAX_RESULT_CHARS) {
            return collapsed;
        }
        return collapsed.substring(0, MAX_RESULT_CHARS) + "…（共 " + result.length() + " 字符）";
    }

    /**
     * 参数回显截断：超长时保留前段并标注省略字符数
     */
    private static String truncateArgs(String arguments) {
        if (arguments == null || arguments.length() <= MAX_ARGS_CHARS) {
            return arguments;
        }
        return arguments.substring(0, MAX_ARGS_CHARS) + "...[省略 "
                + (arguments.length() - MAX_ARGS_CHARS) + " 字符]";
    }
}
