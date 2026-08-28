package com.greensamcli.client;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatResponse;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenAiStreamingChatClient} SSE 解析测试。
 *
 * <p>用 MockWebServer 模拟 SSE 事件流，覆盖三类增量：
 * 思考（{@code reasoning_content}，推理型模型）、文本（{@code content}）、
 * 流末尾的 usage 统计事件（不含 choices）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
class OpenAiStreamingChatClientTest {

    private MockWebServer mockServer;
    private OpenAiStreamingChatClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        client = new OpenAiStreamingChatClient(
                new OkHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
                "test-key", mockServer.url("/v1").toString(), "gpt-4o");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void sendStreaming_解析思考文本与用量回调() throws Exception {
        // SSE 流：思考增量 → 文本增量 → 结束增量 → usage 统计（无 choices）→ [DONE]
        String sseBody = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"正在思考..."}}]}

                data: {"choices":[{"index":0,"delta":{"content":"你好"}}]}

                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}

                data: [DONE]

                """;
        mockServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"));

        List<String> reasoningDeltas = new ArrayList<>();
        List<String> contentDeltas = new ArrayList<>();
        List<ChatResponse.Usage> usages = new ArrayList<>();
        List<ChatMessage> completed = new ArrayList<>();

        client.sendStreaming(List.of(ChatMessage.user("你好")), null, new StreamCallback() {
            @Override
            public void onContentDelta(String delta) {
                contentDeltas.add(delta);
            }

            @Override
            public void onReasoningDelta(String delta) {
                reasoningDeltas.add(delta);
            }

            @Override
            public void onUsage(ChatResponse.Usage usage) {
                usages.add(usage);
            }

            @Override
            public void onToolCallDelta(com.greensamcli.model.ToolCall toolCall) {
            }

            @Override
            public void onComplete(ChatMessage fullAssistantMessage) {
                completed.add(fullAssistantMessage);
            }

            @Override
            public void onError(Exception e) {
            }
        });

        assertEquals(List.of("正在思考..."), reasoningDeltas);
        assertEquals(List.of("你好"), contentDeltas);
        assertEquals(1, usages.size());
        assertEquals(120, usages.get(0).getPromptTokens());
        assertEquals(30, usages.get(0).getCompletionTokens());
        assertEquals(1, completed.size());
        assertEquals("你好", completed.get(0).getContent());

        // 请求体必须携带 stream_options.include_usage，服务端才会返回 usage 事件
        RecordedRequest recorded = mockServer.takeRequest();
        assertTrue(recorded.getBody().readUtf8().contains("\"include_usage\":true"),
                "流式请求应携带 stream_options.include_usage");
    }

    @Test
    void sendStreaming_内联think标签_剥离为思考回调() throws Exception {
        // GLM 系端点风格：思考过程以内联 <think> 标签混在 content 里，
        // 且标签可能跨 delta 分裂（"<th" + "ink>"）
        String sseBody = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"<th"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"ink>用户打招呼，简短回应</think>"}}]}

                data: {"choices":[{"index":0,"delta":{"content":"\\n\\n你好！有什么可以帮你？"}}]}

                data: [DONE]

                """;
        mockServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"));

        List<String> reasoningDeltas = new ArrayList<>();
        List<String> contentDeltas = new ArrayList<>();
        List<ChatMessage> completed = new ArrayList<>();

        client.sendStreaming(List.of(ChatMessage.user("你好")), null, new StreamCallback() {
            @Override
            public void onContentDelta(String delta) {
                contentDeltas.add(delta);
            }

            @Override
            public void onReasoningDelta(String delta) {
                reasoningDeltas.add(delta);
            }

            @Override
            public void onUsage(ChatResponse.Usage usage) {
            }

            @Override
            public void onToolCallDelta(com.greensamcli.model.ToolCall toolCall) {
            }

            @Override
            public void onComplete(ChatMessage fullAssistantMessage) {
                completed.add(fullAssistantMessage);
            }

            @Override
            public void onError(Exception e) {
            }
        });

        assertEquals(List.of("用户打招呼，简短回应"), reasoningDeltas);
        assertEquals(List.of("\n\n你好！有什么可以帮你？"), contentDeltas);
        // 最终消息只含正文，思考段不得进入对话历史
        assertEquals(1, completed.size());
        assertEquals("\n\n你好！有什么可以帮你？", completed.get(0).getContent());
    }

    @Test
    void sendStreaming_工具调用增量_后续空串id与name不覆盖真实值() throws Exception {
        // 工具调用增量的分片契约：id / type / function.name 只在首个分片出现，
        // 后续分片中这些字段可能缺省、为 null 或重复下发空串（部分兼容端点即如此），
        // 只有 arguments 是片段。累积的统一规则是"空值一律忽略"——
        // 空值覆盖累积值曾导致工具名被清成空串，执行时报"未知工具: "
        String sseBody = """
                data: {"choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"id":"call_001","type":"function","function":{"name":"execute_command","arguments":""},"index":0}]}}]}

                data: {"choices":[{"index":0,"delta":{"content":"","role":"assistant","tool_calls":[{"id":"","type":"function","function":{"name":"","arguments":"{\\"command\\": \\"dir "},"index":0}]}}]}

                data: {"choices":[{"index":0,"delta":{"content":"","role":"assistant","tool_calls":[{"id":"","type":"function","function":{"name":"","arguments":"/w\\"}"},"index":0}]}}]}

                data: [DONE]

                """;
        mockServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"));

        List<ChatMessage> completed = new ArrayList<>();

        client.sendStreaming(List.of(ChatMessage.user("列目录")), null, new StreamCallback() {
            @Override
            public void onContentDelta(String delta) {
            }

            @Override
            public void onReasoningDelta(String delta) {
            }

            @Override
            public void onUsage(ChatResponse.Usage usage) {
            }

            @Override
            public void onToolCallDelta(com.greensamcli.model.ToolCall toolCall) {
            }

            @Override
            public void onComplete(ChatMessage fullAssistantMessage) {
                completed.add(fullAssistantMessage);
            }

            @Override
            public void onError(Exception e) {
            }
        });

        assertEquals(1, completed.size());
        ChatMessage assistant = completed.get(0);
        assertEquals(1, assistant.getToolCalls().size());
        com.greensamcli.model.ToolCall toolCall = assistant.getToolCalls().get(0);
        // 首个增量累积到的 id / name 不被后续增量的空串覆盖
        assertEquals("call_001", toolCall.getId());
        assertEquals("function", toolCall.getType());
        assertEquals("execute_command", toolCall.getFunction().getName());
        // arguments 片段正常拼接为完整 JSON
        assertEquals("{\"command\": \"dir /w\"}", toolCall.getFunction().getArguments());
    }

    @Test
    void sendStreaming_紧凑data前缀_同样可解析() throws Exception {
        // 部分兼容网关按 SSE 规范发紧凑形式 "data:{...}"（冒号后无空格）
        String sseBody = """
                data:{"choices":[{"index":0,"delta":{"content":"你好"}}]}

                data:[DONE]

                """;
        mockServer.enqueue(new MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"));

        List<String> contentDeltas = new ArrayList<>();
        List<ChatMessage> completed = new ArrayList<>();

        client.sendStreaming(List.of(ChatMessage.user("你好")), null, new StreamCallback() {
            @Override
            public void onContentDelta(String delta) {
                contentDeltas.add(delta);
            }

            @Override
            public void onReasoningDelta(String delta) {
            }

            @Override
            public void onUsage(ChatResponse.Usage usage) {
            }

            @Override
            public void onToolCallDelta(com.greensamcli.model.ToolCall toolCall) {
            }

            @Override
            public void onComplete(ChatMessage fullAssistantMessage) {
                completed.add(fullAssistantMessage);
            }

            @Override
            public void onError(Exception e) {
            }
        });

        assertEquals(List.of("你好"), contentDeltas);
        assertEquals("你好", completed.get(0).getContent());
    }
}
