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

import static org.junit.jupiter.api.Assertions.*;

class OpenAiChatClientTest {

    private MockWebServer mockServer;
    private OpenAiChatClient client;
    private final ObjectMapper mapper = new ObjectMapper();

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
}
