package com.greensamcli.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greensamcli.model.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatClientTest {

    private MockWebServer mockServer;
    private OpenAiChatClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 最小合法的文本响应体，供超时相关用例复用
     */
    private static final String TEXT_RESPONSE_BODY = """
            {
              "id": "chatcmpl-test",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "Hello! How can I help you?"
                },
                "finish_reason": "stop"
              }]
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("/v1").toString();
        client = new OpenAiChatClient(
                new OkHttpClient(), mapper, "test-key", baseUrl, "gpt-4o");
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void send_simpleTextResponse() throws Exception {
        String mockResponseBody = """
                {
                  "id": "chatcmpl-test",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "Hello! How can I help you?"
                    },
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 8,
                    "total_tokens": 18
                  }
                }
                """;

        mockServer.enqueue(new MockResponse()
                .setBody(mockResponseBody)
                .setHeader("Content-Type", "application/json"));

        List<ChatMessage> messages = List.of(ChatMessage.user("Hello"));
        ChatResponse response = client.send(messages, null);

        assertNotNull(response);
        assertEquals("Hello! How can I help you?", response.getAssistantMessage().getContent());
        assertEquals("assistant", response.getAssistantMessage().getRole());
        assertFalse(response.hasToolCalls());
    }

    @Test
    void send_toolCallResponse() throws Exception {
        String mockResponseBody = """
                {
                  "id": "chatcmpl-test",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": null,
                      "tool_calls": [{
                        "id": "call_abc123",
                        "type": "function",
                        "function": {
                          "name": "read_file",
                          "arguments": "{\\"path\\":\\"/tmp/test.txt\\"}"
                        }
                      }]
                    },
                    "finish_reason": "tool_calls"
                  }]
                }
                """;

        mockServer.enqueue(new MockResponse()
                .setBody(mockResponseBody)
                .setHeader("Content-Type", "application/json"));

        List<ChatMessage> messages = List.of(ChatMessage.user("Read /tmp/test.txt"));
        ChatResponse response = client.send(messages, null);

        assertTrue(response.hasToolCalls());
        ToolCall toolCall = response.getAssistantMessage().getToolCalls().get(0);
        assertEquals("call_abc123", toolCall.getId());
        assertEquals("read_file", toolCall.getFunction().getName());
        assertEquals("{\"path\":\"/tmp/test.txt\"}", toolCall.getFunction().getArguments());
    }

    @Test
    void send_apiError_throwsException() {
        mockServer.enqueue(new MockResponse().setResponseCode(401).setBody("{\"error\":\"unauthorized\"}"));

        List<ChatMessage> messages = List.of(ChatMessage.user("Hello"));

        assertThrows(OpenAiChatClient.ChatClientException.class, () -> client.send(messages, null));
    }

    @Test
    void send_slowResponse_withinReadTimeout_succeeds() throws Exception {
        // 读超时放宽到 3s，服务端 1s 后才发响应体：应正常完成（锁死读超时配置生效的语义）
        OpenAiChatClient patientClient = new OpenAiChatClient(
                new OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build(),
                mapper, "test-key", mockServer.url("/v1").toString(), "gpt-4o");

        mockServer.enqueue(new MockResponse()
                .setBodyDelay(1, TimeUnit.SECONDS)
                .setBody(TEXT_RESPONSE_BODY)
                .setHeader("Content-Type", "application/json"));

        ChatResponse response = patientClient.send(List.of(ChatMessage.user("Hello")), null);

        assertEquals("Hello! How can I help you?", response.getAssistantMessage().getContent());
    }

    @Test
    void send_slowResponse_exceedsReadTimeout_throws() {
        // 读超时收紧到 200ms，服务端 2s 后才发响应体：应抛 ChatClientException（内因 SocketTimeoutException）
        OpenAiChatClient impatientClient = new OpenAiChatClient(
                new OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build(),
                mapper, "test-key", mockServer.url("/v1").toString(), "gpt-4o");

        mockServer.enqueue(new MockResponse()
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody(TEXT_RESPONSE_BODY)
                .setHeader("Content-Type", "application/json"));

        assertThrows(OpenAiChatClient.ChatClientException.class,
                () -> impatientClient.send(List.of(ChatMessage.user("Hello")), null));
    }

    @Test
    void send_内联think标签_剥离后返回正文() throws Exception {
        // GLM 系端点：思考以内联 <think> 标签混在 content 里，同步模式同样要剥离
        String thinkBody = """
                {
                  "id": "chatcmpl-think",
                  "choices": [{
                    "index": 0,
                    "message": {
                      "role": "assistant",
                      "content": "<think>思考过程</think>\\n\\n正式回答"
                    },
                    "finish_reason": "stop"
                  }]
                }
                """;
        mockServer.enqueue(new MockResponse()
                .setBody(thinkBody)
                .setHeader("Content-Type", "application/json"));

        ChatResponse response = client.send(List.of(ChatMessage.user("Hello")), null);

        assertEquals("\n\n正式回答", response.getAssistantMessage().getContent());
    }
}
