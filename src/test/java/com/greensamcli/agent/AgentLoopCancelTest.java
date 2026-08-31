package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.greensamcli.client.ChatClient;
import com.greensamcli.client.StreamCallback;
import com.greensamcli.client.StreamingChatClient;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatResponse;
import com.greensamcli.model.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentLoop 中断机制测试（批次②）。
 *
 * <p>全部使用假 client 与自定义线程模拟「外部信号线程调用 cancel()」，
 * 不构造真实终端、不访问网络（JLine 测试挂起教训）。覆盖：</p>
 * <ul>
 *   <li>空闲时 cancel() 为无操作；</li>
 *   <li>LLM 响应返回时已取消 → 响应丢弃、不入历史；</li>
 *   <li>工具执行期间取消 → 剩余 tool_call 补「用户已中断」结果、不再发起 LLM 调用；</li>
 *   <li>取消后对话可直接继续（历史结构合法）；</li>
 *   <li>流式模式下取消立即生效，迟到的流式事件被丢弃不打到提示符。</li>
 * </ul>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
class AgentLoopCancelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry();
    }

    // ==================== 同步模式 ====================

    @Test
    void cancel_空闲时调用为无操作() {
        AgentLoop loop = new AgentLoop(
                (messages, tools) -> textResponse("Hello!"),
                toolRegistry, objectMapper, "test");

        // 无进行中的回合，cancel() 不应影响下一回合
        loop.cancel();

        assertEquals("Hello!", loop.run("Hi", null).getContent());
    }

    @Test
    void cancel_响应返回时已取消_响应丢弃不入历史() {
        AtomicReference<AgentLoop> loopRef = new AtomicReference<>();
        AtomicInteger apiCalls = new AtomicInteger();
        ChatClient client = (messages, tools) -> {
            apiCalls.incrementAndGet();
            // 模拟信号线程在 LLM 调用期间收到 Ctrl+C
            loopRef.get().cancel();
            return textResponse("迟到的回复");
        };
        AgentLoop loop = new AgentLoop(client, toolRegistry, objectMapper, "test");
        loopRef.set(loop);

        assertThrows(AgentCancelledException.class, () -> loop.run("Hi", null));

        assertEquals(1, apiCalls.get());
        // 历史：system + user，取消的响应没有进入历史
        assertEquals(2, loop.getConversationHistory().size());
    }

    @Test
    void cancel_工具执行期间中断_剩余调用补用户已中断() {
        AtomicReference<AgentLoop> loopRef = new AtomicReference<>();
        AtomicInteger apiCalls = new AtomicInteger();
        ChatClient client = (messages, tools) -> {
            if (apiCalls.incrementAndGet() > 1) {
                throw new AssertionError("取消后不应再发起 LLM 调用");
            }
            return toolCallsResponse("call_1", "echo_a", "call_2", "echo_b");
        };
        toolRegistry.register(new Tool() {
            @Override public String getName() { return "echo_a"; }
            @Override public String getDescription() { return "执行时触发取消"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) {
                // 模拟 Ctrl+C 到达时该工具正在执行
                loopRef.get().cancel();
                return "ok_a";
            }
        });
        toolRegistry.register(new Tool() {
            @Override public String getName() { return "echo_b"; }
            @Override public String getDescription() { return "取消后不应被执行"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) {
                throw new AssertionError("取消后剩余工具不应执行");
            }
        });

        AgentLoop loop = new AgentLoop(client, toolRegistry, objectMapper, "test");
        loopRef.set(loop);

        List<String> events = new ArrayList<>();
        ToolCallListener listener = new ToolCallListener() {
            @Override public void onToolCallStarted(ToolCall call) { }
            @Override public void onToolCallCompleted(String name, String result) { }
            @Override public void onToolCallFailed(String name, String error) {
                events.add(name + ":" + error);
            }
        };

        assertThrows(AgentCancelledException.class, () -> loop.run("Go", listener));

        List<ChatMessage> history = loop.getConversationHistory();
        // system + user + assistant(tool_calls) + echo_a 正常结果 + echo_b 中断占位
        assertEquals(5, history.size());
        assertEquals("tool", history.get(3).getRole());
        assertEquals("ok_a", history.get(3).getContent());
        assertEquals("用户已中断本轮执行，该工具调用未执行。", history.get(4).getContent());
        assertEquals("call_2", history.get(4).getToolCallId());
        assertTrue(events.contains("echo_b:用户已中断"));
    }

    @Test
    void cancel_中断后对话可继续() {
        AtomicReference<AgentLoop> loopRef = new AtomicReference<>();
        AtomicBoolean firstCall = new AtomicBoolean(true);
        ChatClient client = (messages, tools) -> {
            if (firstCall.compareAndSet(true, false)) {
                return toolCallsResponse("call_1", "echo_a", "call_2", "echo_b");
            }
            return textResponse("继续干活");
        };
        toolRegistry.register(new Tool() {
            @Override public String getName() { return "echo_a"; }
            @Override public String getDescription() { return "执行时触发取消"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) {
                loopRef.get().cancel();
                return "ok_a";
            }
        });
        toolRegistry.register(new Tool() {
            @Override public String getName() { return "echo_b"; }
            @Override public String getDescription() { return "取消后不应被执行"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) {
                throw new AssertionError("取消后剩余工具不应执行");
            }
        });

        AgentLoop loop = new AgentLoop(client, toolRegistry, objectMapper, "test");
        loopRef.set(loop);

        // 第一轮被中断
        assertThrows(AgentCancelledException.class, () -> loop.run("开始", null));

        // 第二轮：历史保持合法（tool_call 都有结果），LLM 能看到中断前的上下文
        ChatMessage reply = loop.run("刚才做到哪了", null);
        assertEquals("继续干活", reply.getContent());

        List<ChatMessage> history = loop.getConversationHistory();
        // system + user + assistant(tool_calls) + tool_a + tool_b占位 + user + assistant
        assertEquals(7, history.size());
        assertEquals("user", history.get(5).getRole());
        assertEquals("刚才做到哪了", history.get(5).getContent());
    }

    // ==================== 流式模式 ====================

    @Test
    void cancel_流式模式_取消立即生效且迟到事件被丢弃() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        StreamingChatClient slowClient = (messages, tools, callback) -> {
            // 模拟真实 SSE：读取发生在独立线程，迟到的完整回复受测试控制投放时机
            Thread fakeSse = new Thread(() -> {
                workerStarted.countDown();
                try {
                    releaseWorker.await();
                } catch (InterruptedException e) {
                    return;
                }
                callback.onContentDelta("迟到的增量");
                callback.onComplete(ChatMessage.assistant("迟到的完整回复"));
            }, "fake-sse");
            fakeSse.setDaemon(true);
            fakeSse.start();
        };

        ChatClient unusedSyncClient = (messages, tools) -> {
            throw new AssertionError("流式模式不应调用同步客户端");
        };
        AgentLoop loop = new AgentLoop(unusedSyncClient, slowClient, toolRegistry, objectMapper, "test");

        List<String> outerEvents = Collections.synchronizedList(new ArrayList<>());
        StreamCallback outer = new StreamCallback() {
            @Override public void onContentDelta(String delta) { outerEvents.add("delta:" + delta); }
            @Override public void onToolCallDelta(ToolCall toolCall) { }
            @Override public void onComplete(ChatMessage message) { outerEvents.add("complete"); }
            @Override public void onError(Exception e) { outerEvents.add("error"); }
        };

        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread round = new Thread(() -> {
            try {
                loop.runStreaming("Hi", null, outer);
            } catch (AgentCancelledException e) {
                cancelled.set(true);
            }
        }, "round");
        round.start();
        assertTrue(workerStarted.await(2, TimeUnit.SECONDS), "流式线程应已启动");

        long startNanos = System.nanoTime();
        loop.cancel();
        round.join(3000);

        assertFalse(round.isAlive(), "cancel 后回合线程应立即退出");
        assertTrue(cancelled.get(), "应抛出 AgentCancelledException");
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        assertTrue(elapsedMs < 2000, "取消应立即生效，实际耗时 " + elapsedMs + "ms");

        // 释放被取消的假流：迟到的增量/完成事件必须被丢弃，不得打到已返回的提示符
        releaseWorker.countDown();
        Thread.sleep(300);
        assertTrue(outerEvents.isEmpty(), "取消后不应再收到任何流式事件: " + outerEvents);
    }

    // ==================== 打桩辅助 ====================

    private ChatResponse textResponse(String content) {
        return new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant(content), "stop")
        ), null);
    }

    /**
     * 构造一次包含多个 tool_call 的响应，参数按 (id1, name1, id2, name2, ...) 成对给出
     */
    private ChatResponse toolCallsResponse(String... idAndName) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < idAndName.length; i += 2) {
            calls.add(ToolCall.builder()
                    .id(idAndName[i])
                    .type("function")
                    .function(new ToolCall.FunctionCall(idAndName[i + 1], "{}"))
                    .build());
        }
        return new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistantWithToolCalls(calls), "tool_calls")
        ), null);
    }
}
