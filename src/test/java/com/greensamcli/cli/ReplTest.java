package com.greensamcli.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greensamcli.agent.AgentLoop;
import com.greensamcli.agent.ToolRegistry;
import com.greensamcli.client.ChatClient;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatResponse;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repl 内置命令分发测试。
 *
 * <p>覆盖 /exit、/help、/clear 的正常路径与边界（带多余参数、未知命令、
 * 普通文本不拦截），并验证命令处理不会触发模型调用。
 * 通过覆写 {@code clearScreen} 跳过真实终端输出——在无 TTY 的测试进程里
 * 构造 JLine 终端会阻塞，故测试不触碰任何终端。</p>
 *
 * @author Macro Ray
 * @since 2026-08-18
 */
class ReplTest {

    private final List<String> systemMessages = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    private AgentLoop agentLoop;
    private Repl repl;

    @BeforeEach
    void setUp() {
        // 默认打桩：任何命令若误触模型调用会直接抛异常，测试即失败
        agentLoop = newLoop((messages, tools) -> {
            throw new IllegalStateException("内置命令不应触发模型调用");
        });
        repl = new NoScreenClearRepl(agentLoop, recordingRenderer());
    }

    @Test
    void exit命令_返回退出并提示再见() {
        assertEquals(Repl.BuiltinCommandResult.EXIT, repl.handleBuiltinCommand("/exit", null));
        assertEquals(List.of("再见！"), systemMessages);
    }

    @Test
    void quit命令_返回退出() {
        assertEquals(Repl.BuiltinCommandResult.EXIT, repl.handleBuiltinCommand("/quit", null));
    }

    @Test
    void exit命令带多余参数_仍退出() {
        assertEquals(Repl.BuiltinCommandResult.EXIT, repl.handleBuiltinCommand("/exit now", null));
    }

    @Test
    void help命令_返回已处理() {
        assertEquals(Repl.BuiltinCommandResult.HANDLED, repl.handleBuiltinCommand("/help", null));
        assertTrue(errors.isEmpty());
    }

    @Test
    void help命令带多余参数_返回已处理() {
        assertEquals(Repl.BuiltinCommandResult.HANDLED, repl.handleBuiltinCommand("/help me", null));
        assertTrue(errors.isEmpty());
    }

    @Test
    void clear命令_清空对话历史并提示() {
        AgentLoop loop = newLoopWithOneRoundConversation();
        Repl replWithHistory = new NoScreenClearRepl(loop, recordingRenderer());
        assertEquals(3, loop.getConversationHistory().size());

        Repl.BuiltinCommandResult result = replWithHistory.handleBuiltinCommand("/clear", null);

        assertEquals(Repl.BuiltinCommandResult.HANDLED, result);
        assertTrue(loop.getConversationHistory().isEmpty());
        assertEquals(List.of("对话历史已清空。"), systemMessages);
    }

    @Test
    void clear命令带多余参数_同样清空历史() {
        AgentLoop loop = newLoopWithOneRoundConversation();
        Repl replWithHistory = new NoScreenClearRepl(loop, recordingRenderer());

        Repl.BuiltinCommandResult result = replWithHistory.handleBuiltinCommand("/clear 现在", null);

        assertEquals(Repl.BuiltinCommandResult.HANDLED, result);
        assertTrue(loop.getConversationHistory().isEmpty());
        assertEquals(List.of("对话历史已清空。"), systemMessages);
    }

    @Test
    void 未知斜杠命令_报错且不动历史() {
        AgentLoop loop = newLoopWithOneRoundConversation();
        Repl replWithHistory = new NoScreenClearRepl(loop, recordingRenderer());

        Repl.BuiltinCommandResult result = replWithHistory.handleBuiltinCommand("/foo bar", null);

        assertEquals(Repl.BuiltinCommandResult.HANDLED, result);
        assertEquals(3, loop.getConversationHistory().size());
        assertEquals(List.of("未知命令：/foo"), errors);
    }

    @Test
    void 普通文本_不是命令() {
        assertEquals(Repl.BuiltinCommandResult.NOT_A_COMMAND, repl.handleBuiltinCommand("你好，帮我看下代码", null));
        assertTrue(systemMessages.isEmpty());
        assertTrue(errors.isEmpty());
    }

    /**
     * 跳过清屏输出的测试替身：终端参数不会被使用
     */
    private static class NoScreenClearRepl extends Repl {

        NoScreenClearRepl(AgentLoop agentLoop, CliRenderer renderer) {
            super(agentLoop, renderer, false);
        }

        @Override
        void clearScreen(Terminal terminal) {
            // 测试环境无真实终端，跳过清屏序列输出
        }
    }

    /**
     * 构造打桩渲染器：记录系统消息与错误，便于断言
     */
    private CliRenderer recordingRenderer() {
        return new CliRenderer() {
            @Override
            public void displayUser(String text) {
            }

            @Override
            public void displaySystem(String text) {
                systemMessages.add(text);
            }

            @Override
            public void displayAssistant(String text) {
            }

            @Override
            public void displayAssistantDelta(String delta) {
            }

            @Override
            public void displayAssistantEnd() {
            }

            @Override
            public void displayReasoningDelta(String delta) {
            }

            @Override
            public void displayToolCall(String toolName, String arguments) {
            }

            @Override
            public void displayToolResult(String toolName, String result) {
            }

            @Override
            public void displayError(String message) {
                errors.add(message);
            }

            @Override
            public void displayTokenUsage(int roundPromptTokens, int roundCompletionTokens,
                                          int totalPromptTokens, int totalCompletionTokens) {
            }
        };
    }

    private AgentLoop newLoop(ChatClient client) {
        return new AgentLoop(client, new ToolRegistry(), new ObjectMapper(), "test");
    }

    /**
     * 构造已跑完一轮对话（system + user + assistant，共 3 条）的 AgentLoop
     */
    private AgentLoop newLoopWithOneRoundConversation() {
        ChatResponse response = new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant("好的"), "stop")
        ), null);
        AgentLoop loop = newLoop((messages, tools) -> response);
        loop.run("你好", null);
        return loop;
    }
}
