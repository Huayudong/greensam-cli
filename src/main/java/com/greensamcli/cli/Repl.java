package com.greensamcli.cli;

import com.greensamcli.agent.AgentLoop;
import com.greensamcli.agent.ToolCallListener;
import com.greensamcli.client.StreamCallback;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatResponse;
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
 * <p>终端所有输出经 {@link CliRenderer} 渲染：每类事件带 emoji 前缀
 * （🥷🏻 用户 / 🤖 回答 / 💭 思考 / 🛠️ 工具 / 📊 token 消耗），
 * 让 Agent 链路的每一步在终端上可见。</p>
 *
 * <p>支持两种运行模式：</p>
 * <ul>
 *   <li>同步模式（runBlocking）：等待 Agent 完成所有推理后一次性显示</li>
 *   <li>流式模式（runStreaming）：思考与回答逐字显示（打字机效果）</li>
 * </ul>
 */
@Slf4j
public class Repl {

    private static final String PROMPT = "> ";

    private final AgentLoop agentLoop;
    private final CliRenderer renderer;
    /**
     * 是否使用流式输出模式
     */
    private final boolean useStreaming;
    /**
     * 会话累计 token 用量（输入），/clear 时重置
     */
    private int sessionPromptTokens;
    /**
     * 会话累计 token 用量（输出），/clear 时重置
     */
    private int sessionCompletionTokens;
    /**
     * 待渲染的本轮 token 用量：AgentLoop 在回合结束时回调，但此时流式回答块
     * 尚未收尾，直接渲染会让 📊 粘在回答末行——先暂存，
     * 待回答块关闭（displayAssistantEnd / displayAssistant 之后）再渲染
     */
    private ChatResponse.Usage pendingRoundUsage;

    public Repl(AgentLoop agentLoop, CliRenderer renderer, boolean useStreaming) {
        this.agentLoop = agentLoop;
        this.renderer = renderer;
        this.useStreaming = useStreaming;
    }

    private static final String BANNER = """
             
              ██████╗ ██████╗ ███████╗███████╗███╗   ██╗███████╗ █████╗ ███╗   ███╗     ██████╗██╗     ██╗
             ██╔════╝ ██╔══██╗██╔════╝██╔════╝████╗  ██║██╔════╝██╔══██╗████╗ ████║    ██╔════╝██║     ██║
             ██║  ███╗██████╔╝█████╗  █████╗  ██╔██╗ ██║███████╗███████║██╔████╔██║    ██║     ██║     ██║
             ██║   ██║██╔══██╗██╔══╝  ██╔══╝  ██║╚██╗██║╚════██║██╔══██║██║╚██╔╝██║    ██║     ██║     ██║
             ╚██████╔╝██║  ██║███████╗███████╗██║ ╚████║███████║██║  ██║██║ ╚═╝ ██║    ╚██████╗███████╗██║
              ╚═════╝ ╚═╝  ╚═╝╚══════╝╚══════╝╚═╝  ╚═══╝╚══════╝╚═╝  ╚═╝╚═╝     ╚═╝     ╚═════╝╚══════╝╚═╝
                                                                      基于 Java 的 Agent CLI v1.0.0
             """;

    /**
     * 启动 REPL 主循环，直到用户输入 /exit 或 Ctrl+D
     */
    public void run() {
        // 初始化 JLine3 终端
        Terminal terminal;
        try {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)   // IDEA 等非 TTY 环境下静默降级，不输出警告
                    .build();
        } catch (IOException e) {
            log.error("终端初始化失败", e);
            return;
        }

        // 创建行读取器，支持命令历史、自动补全等
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .build();

        System.out.println(BANNER);
        System.out.println();
        renderer.displaySystem("输入 /help 查看可用命令，/exit 退出。");

        // 工具调用监听器：将 AgentLoop 中的工具执行事件与用量统计转发给渲染器
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

            @Override
            public void onRoundUsage(ChatResponse.Usage usage) {
                // 先累计会话用量并暂存本轮数据，渲染推迟到回答块关闭之后
                // （保证"先答案、后统计"的版式，避免 📊 粘在回答末行）
                sessionPromptTokens += usage.getPromptTokens();
                sessionCompletionTokens += usage.getCompletionTokens();
                pendingRoundUsage = usage;
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

                // 内置命令处理（/help、/clear、/exit 等）
                BuiltinCommandResult result = handleBuiltinCommand(input, terminal);
                if (result == BuiltinCommandResult.EXIT) {
                    break;
                }
                if (result == BuiltinCommandResult.HANDLED) {
                    continue;
                }

                // 普通输入：回显（🥷🏻），再根据配置选择同步或流式模式
                renderer.displayUser(input);
                if (useStreaming) {
                    runStreaming(input, listener);
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
            // 忽略关闭时的异常
        }
    }

    /**
     * 内置命令处理结果。
     */
    enum BuiltinCommandResult {
        /** 非内置命令，应作为普通输入交给 Agent 处理 */
        NOT_A_COMMAND,
        /** 命令已处理，继续读取下一行输入 */
        HANDLED,
        /** 退出程序 */
        EXIT
    }

    /**
     * 处理内置命令（/exit、/help、/clear）。
     *
     * <p>命令按首个单词匹配，命令词之后的参数被忽略
     * （如「/clear 现在」等价于「/clear」），避免带参数的输入落入未知命令分支；
     * 其余以 "/" 开头的输入视为未知命令拦截，不送给模型。</p>
     *
     * @param input    用户输入（已去除首尾空白，非空）
     * @param terminal 当前终端，/clear 时用于输出清屏序列
     * @return 处理结果：非命令 / 已处理 / 退出
     */
    BuiltinCommandResult handleBuiltinCommand(String input, Terminal terminal) {
        String command = input.split("\\s+", 2)[0];

        if (command.equals("/exit") || command.equals("/quit")) {
            renderer.displaySystem("再见！");
            return BuiltinCommandResult.EXIT;
        }

        if (command.equals("/help")) {
            System.out.println("可用命令：");
            System.out.println("  /help   - 显示本帮助");
            System.out.println("  /clear  - 清空对话历史并清屏");
            System.out.println("  /exit   - 退出程序");
            return BuiltinCommandResult.HANDLED;
        }

        if (command.equals("/clear")) {
            clearScreen(terminal);
            agentLoop.clearHistory();
            sessionPromptTokens = 0;
            sessionCompletionTokens = 0;
            pendingRoundUsage = null;
            renderer.displaySystem("对话历史已清空。");
            return BuiltinCommandResult.HANDLED;
        }

        // 未知命令拦截（防止以 "/" 开头的普通文本被当成命令送给模型）
        if (command.startsWith("/")) {
            renderer.displayError("未知命令：" + command);
            return BuiltinCommandResult.HANDLED;
        }

        return BuiltinCommandResult.NOT_A_COMMAND;
    }

    /**
     * 清空终端屏幕。
     *
     * <p>统一通过 {@link Terminal#writer()} 输出 ANSI 清屏序列（清屏 + 光标归位），
     * 不直接写 System.out：JLine 真终端（jansi provider）的 writer 会把 ANSI
     * 翻译为 Windows 原生控制台操作，经典 conhost 上同样生效；
     * dumb 终端下 writer 即 System.out，由控制台自行解释 ANSI。
     * 包级可见便于测试中覆写为空实现，避免触碰真实终端。</p>
     *
     * @param terminal 当前终端
     */
    void clearScreen(Terminal terminal) {
        log.info("执行 /clear 清屏，当前终端类型：{}", terminal.getType());
        terminal.writer().print("\033[2J\033[H");
        terminal.writer().flush();
        terminal.flush();
    }

    /**
     * 同步模式：等待 Agent 完整响应后一次性显示
     */
    private void runBlocking(String input, ToolCallListener listener) {
        try {
            ChatMessage response = agentLoop.run(input, listener);
            renderer.displayAssistant(response.getContent());
            renderPendingUsage();
        } catch (Exception e) {
            pendingRoundUsage = null;
            renderer.displayError(e.getMessage());
        }
    }

    /**
     * 渲染暂存的本轮 token 用量（📊），无暂存则跳过。
     * 必须在回答块完全关闭之后调用，保证"先答案、后统计"的版式。
     */
    private void renderPendingUsage() {
        if (pendingRoundUsage == null) {
            return;
        }
        renderer.displayTokenUsage(
                pendingRoundUsage.getPromptTokens(), pendingRoundUsage.getCompletionTokens(),
                sessionPromptTokens, sessionCompletionTokens);
        pendingRoundUsage = null;
    }

    /**
     * 流式模式：思考（💭）与回答（🤖）逐字显示（打字机效果）。
     *
     * <p>文本/思考增量统一交给渲染器管理前缀与颜色，
     * 结束时由 {@code onComplete} 收尾（关闭流式块）。</p>
     * <p>工具调用（如果有的话）通过 listener 回调显示，
     * 不受流式模式影响（工具执行本身是同步的）。</p>
     */
    private void runStreaming(String input, ToolCallListener listener) {
        try {
            agentLoop.runStreaming(input, listener, new StreamCallback() {
                @Override
                public void onContentDelta(String delta) {
                    // 每收到一个文本增量就立即渲染，实现打字机效果
                    renderer.displayAssistantDelta(delta);
                }

                @Override
                public void onReasoningDelta(String delta) {
                    renderer.displayReasoningDelta(delta);
                }

                @Override
                public void onToolCallDelta(ToolCall toolCall) {
                    // 工具调用增量通过 ToolCallListener（listener）显示，这里不处理
                }

                @Override
                public void onComplete(ChatMessage fullAssistantMessage) {
                    // 流结束，关闭流式块后渲染统计（消息已被 AgentLoop 追加到历史）
                    renderer.displayAssistantEnd();
                    renderPendingUsage();
                }

                @Override
                public void onError(Exception e) {
                    // 错误详情由 AgentLoop 抛异常后统一渲染，这里只负责关闭流式块，
                    // 避免"回调渲染一次 + 异常再渲染一次"的重复显示
                    renderer.displayAssistantEnd();
                }
            });
        } catch (Exception e) {
            pendingRoundUsage = null;
            renderer.displayAssistantEnd();
            renderer.displayError(e.getMessage());
        }
    }
}
