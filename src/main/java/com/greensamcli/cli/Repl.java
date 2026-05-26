package com.greensamcli.cli;

import com.greensamcli.agent.AgentLoop;
import com.greensamcli.agent.ToolCallListener;
import com.greensamcli.client.StreamCallback;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ToolCall;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

/**
 * REPL（Read-Eval-Print Loop）——终端交互循环。
 *
 * <p>这是用户与 Agent 交互的入口。使用 JLine3 库提供：</p>
 * <ul>
 *   <li>命令历史（上下箭头浏览之前的输入）</li>
 *   <li>Ctrl+C 中断当前输入</li>
 *   <li>Ctrl+D 退出程序</li>
 * </ul>
 *
 * <h3>交互流程</h3>
 * <pre>
 * ┌──────────────────────────────────────────┐
 * │  显示提示符 "> "                          │
 * │       ↓                                   │
 * │  读取用户输入（JLine3 LineReader）         │
 * │       ↓                                   │
 * │  是内置命令？（/help, /clear, /exit）      │
 * │    ├── 是 → 执行命令，回到顶部             │
 * │    └── 否 ↓                               │
 * │  调用 AgentLoop.run() / runStreaming()    │
 * │       ↓                                   │
 * │  显示 AI 回复（通过 CliRenderer）          │
 * │       ↓                                   │
 * │  回到顶部                                 │
 * └──────────────────────────────────────────┘
 * </pre>
 *
 * <p>支持两种运行模式：</p>
 * <ul>
 *   <li>同步模式（runBlocking）：等待 Agent 完成所有推理后一次性显示</li>
 *   <li>流式模式（runStreaming）：AI 文字逐字显示（打字机效果）</li>
 * </ul>
 */
@Slf4j
public class Repl {

    private static final String PROMPT = "> ";
    /** ANSI 颜色码，用于流式模式下 AI 回复的着色 */
    private static final String RESET = "\033[0m";
    private static final String CYAN = "\033[36m";

    private final AgentLoop agentLoop;
    private final CliRenderer renderer;
    /** 是否使用流式输出模式 */
    private final boolean useStreaming;

    public Repl(AgentLoop agentLoop, CliRenderer renderer, boolean useStreaming) {
        this.agentLoop = agentLoop;
        this.renderer = renderer;
        this.useStreaming = useStreaming;
    }

    private static final String BANNER = "\033[32m"
            + "                                                                                                                                 \n" +
            "  ,----..                                                                            ____            ,----..    ,--,             \n" +
            " /   /   \\                                                                         ,'  , `.         /   /   \\ ,--.'|     ,--,    \n" +
            "|   :     :   __  ,-.                        ,---,                              ,-+-,.' _ |        |   :     :|  | :   ,--.'|    \n" +
            ".   |  ;. / ,' ,'/ /|                    ,-+-. /  |  .--.--.                 ,-+-. ;   , ||        .   |  ;. /:  : '   |  |,     \n" +
            ".   ; /--`  '  | |' | ,---.     ,---.   ,--.'|'   | /  /    '    ,--.--.    ,--.'|'   |  ||        .   ; /--` |  ' |   `--'_     \n" +
            ";   | ;  __ |  |   ,'/     \\   /     \\ |   |  ,\"' ||  :  /`./   /       \\  |   |  ,', |  |,        ;   | ;    '  | |   ,' ,'|    \n" +
            "|   : |.' .''  :  / /    /  | /    /  ||   | /  | ||  :  ;_    .--.  .-. | |   | /  | |--'         |   : |    |  | :   '  | |    \n" +
            ".   | '_.' :|  | ' .    ' / |.    ' / ||   | |  | | \\  \\    `.  \\__\\/: . . |   : |  | ,            .   | '___ '  : |__ |  | :    \n" +
            "'   ; : \\  |;  : | '   ;   /|'   ;   /||   | |  |/   `----.   \\ ,\" .--.; | |   : |  |/             '   ; : .'||  | '.'|'  : |__  \n" +
            "'   | '/  .'|  , ; '   |  / |'   |  / ||   | |--'   /  /`--'  //  /  ,.  | |   | |`-'              '   | '/  :;  :    ;|  | '.'| \n" +
            "|   :    /   ---'  |   :    ||   :    ||   |/      '--'.     /;  :   .'   \\|   ;/                  |   :    / |  ,   / ;  :    ; \n" +
            " \\   \\ .'           \\   \\  /  \\   \\  / '---'         `--'---' |  ,     .-./'---'                    \\   \\ .'   ---`-'  |  ,   /  \n" +
            "  `---`              `----'    `----'                          `--`---'                              `---`              ---`-'   \n" +
            "                                                                                                                                 \n"
            + "\033[90m  A CLI Agent built from scratch with Java\033[0m\n";

    /** 启动 REPL 主循环，直到用户输入 /exit 或 Ctrl+D */
    public void run() {
        // 初始化 JLine3 终端
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)   // IDEA 等非 TTY 环境下静默降级，不输出警告
                    .build();
        } catch (IOException e) {
            log.error("Failed to initialize terminal", e);
            return;
        }

        // 创建行读取器，支持命令历史、自动补全等
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

        System.out.println(BANNER);
        System.out.println();
        renderer.displaySystem("Type /help for commands, /exit to quit.");

        // 工具调用监听器：将 AgentLoop 中的工具执行事件转发给渲染器
        ToolCallListener listener = new ToolCallListener() {
            @Override
            public void onToolCallStarted(ToolCall call) {
                renderer.displayToolCall(
                        call.getFunction().getName(),
                        call.getFunction().getArguments()
                );
            }

            @Override
            public void onToolCallCompleted(String toolName, String result) {
                renderer.displayToolResult(toolName, result);
            }

            @Override
            public void onToolCallFailed(String toolName, String error) {
                renderer.displayError(toolName + ": " + error);
            }
        };

        // ---- 主循环 ----
        while (true) {
            try {
                String line = lineReader.readLine(PROMPT);

                if (line == null || line.isBlank()) {
                    continue;
                }

                String input = line.trim();

                // 内置命令处理
                if (input.equals("/exit") || input.equals("/quit")) {
                    renderer.displaySystem("Goodbye!");
                    break;
                }

                if (input.equals("/help")) {
                    System.out.println("Commands:");
                    System.out.println("  /help   - Show this help");
                    System.out.println("  /clear  - Clear conversation history");
                    System.out.println("  /exit   - Exit the program");
                    continue;
                }

                if (input.equals("/clear")) {
                    agentLoop.clearHistory();
                    renderer.displaySystem("Conversation cleared.");
                    continue;
                }

                // 未知命令拦截（防止以 "/" 开头的普通文本被当成命令）
                if (input.startsWith("/")) {
                    renderer.displayError("Unknown command: " + input);
                    continue;
                }

                // 根据配置选择同步或流式模式
                if (useStreaming) {
                    runStreaming(input, listener, terminal);
                } else {
                    runBlocking(input, listener);
                }

            } catch (UserInterruptException e) {
                // 用户按 Ctrl+C：不退出程序，只是中断当前输入，重新显示提示符
                System.out.println();
                continue;
            } catch (EndOfFileException e) {
                // 用户按 Ctrl+D：退出程序
                break;
            }
        }

        // 关闭终端资源
        try {
            terminal.close();
        } catch (IOException e) {
            // ignore
        }
    }

    /** 同步模式：等待 Agent 完整响应后一次性显示 */
    private void runBlocking(String input, ToolCallListener listener) {
        try {
            ChatMessage response = agentLoop.run(input, listener);
            renderer.displayAssistant(response.getContent());
        } catch (Exception e) {
            renderer.displayError(e.getMessage());
        }
    }

    /**
     * 流式模式：AI 回复逐字显示（打字机效果）。
     *
     * <p>流程：先输出青色 ANSI 码 → 逐字打印 delta → 输出 RESET 码。</p>
     * <p>工具调用（如果有的话）通过 listener 回调显示，
     * 不受流式模式影响（工具执行本身是同步的）。</p>
     */
    private void runStreaming(String input, ToolCallListener listener, Terminal terminal) {
        System.out.println();
        System.out.print(CYAN);

        StringBuilder fullContent = new StringBuilder();

        try {
            agentLoop.runStreaming(input, listener, new StreamCallback() {
                @Override
                public void onContentDelta(String delta) {
                    // 每收到一个文本增量就立即打印，实现打字机效果
                    System.out.print(delta);
                    fullContent.append(delta);
                }

                @Override
                public void onToolCallDelta(ToolCall toolCall) {
                    // 工具调用增量通过 ToolCallListener（listener）显示，这里不处理
                }

                @Override
                public void onComplete(ChatMessage fullAssistantMessage) {
                    // 流结束，不需要额外处理（消息已被 AgentLoop 追加到历史）
                }

                @Override
                public void onError(Exception e) {
                    renderer.displayError(e.getMessage());
                }
            });

            // 重置颜色并换行
            System.out.println(RESET);
            System.out.println();
        } catch (Exception e) {
            System.out.println(RESET);
            renderer.displayError(e.getMessage());
        }
    }
}
