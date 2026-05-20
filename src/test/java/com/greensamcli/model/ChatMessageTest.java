package com.greensamcli.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void systemMessage_serializesCorrectly() throws Exception {
        ChatMessage msg = ChatMessage.system("You are a helpful assistant.");
        String json = mapper.writeValueAsString(msg);
        JsonNode node = mapper.readTree(json);

        assertEquals("system", node.get("role").asText());
        assertEquals("You are a helpful assistant.", node.get("content").asText());
        assertNull(node.get("tool_calls"));
        assertNull(node.get("tool_call_id"));
    }

    @Test
    void userMessage_serializesCorrectly() throws Exception {
        ChatMessage msg = ChatMessage.user("Read the file at /tmp/test.txt");
        String json = mapper.writeValueAsString(msg);
        JsonNode node = mapper.readTree(json);

        assertEquals("user", node.get("role").asText());
        assertEquals("Read the file at /tmp/test.txt", node.get("content").asText());
    }

    @Test
    void toolResultMessage_serializesCorrectly() throws Exception {
        ChatMessage msg = ChatMessage.toolResult("call_abc123", "read_file", "file contents here");
        String json = mapper.writeValueAsString(msg);
        JsonNode node = mapper.readTree(json);

        assertEquals("tool", node.get("role").asText());
        assertEquals("call_abc123", node.get("tool_call_id").asText());
        assertEquals("read_file", node.get("name").asText());
        assertEquals("file contents here", node.get("content").asText());
    }

    @Test
    void assistantWithToolCalls_serializesCorrectly() throws Exception {
        ToolCall toolCall = ToolCall.builder()
                .id("call_abc123")
                .type("function")
                .function(new ToolCall.FunctionCall("read_file", "{\"path\":\"/tmp/test.txt\"}"))
                .build();

        ChatMessage msg = ChatMessage.assistantWithToolCalls(List.of(toolCall));
        String json = mapper.writeValueAsString(msg);
        JsonNode node = mapper.readTree(json);

        assertEquals("assistant", node.get("role").asText());
        assertTrue(node.has("tool_calls"));
        JsonNode tc = node.get("tool_calls").get(0);
        assertEquals("call_abc123", tc.get("id").asText());
        assertEquals("read_file", tc.get("function").get("name").asText());
        assertEquals("{\"path\":\"/tmp/test.txt\"}", tc.get("function").get("arguments").asText());
    }
}
