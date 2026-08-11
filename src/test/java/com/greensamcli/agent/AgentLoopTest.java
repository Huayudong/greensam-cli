package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.client.ChatClient;
import com.greensamcli.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    private ChatClient chatClient;
    private ToolRegistry toolRegistry;
    private ObjectMapper objectMapper;
    private List<ChatResponse> responses;
    private int callCount;

    @BeforeEach
    void setUp() {
        responses = new ArrayList<>();
        callCount = 0;

        chatClient = (messages, tools) -> {
            if (callCount >= responses.size()) {
                throw new RuntimeException("Unexpected API call #" + (callCount + 1));
            }
            return responses.get(callCount++);
        };

        toolRegistry = new ToolRegistry();
        objectMapper = new ObjectMapper();
    }

    @Test
    void run_simpleTextResponse() {
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant("Hello!"), "stop")
        ), null));

        AgentLoop loop = new AgentLoop(chatClient, toolRegistry, objectMapper, "test system prompt");
        ChatMessage result = loop.run("Hi", null);

        assertEquals("Hello!", result.getContent());
        assertEquals("assistant", result.getRole());

        List<ChatMessage> history = loop.getConversationHistory();
        assertEquals(3, history.size()); // system + user + assistant
        assertEquals("system", history.get(0).getRole());
        assertEquals("user", history.get(1).getRole());
        assertEquals("assistant", history.get(2).getRole());
    }

    @Test
    void run_toolCallThenText() {
        Tool echoTool = new Tool() {
            @Override
            public String getName() { return "echo"; }
            @Override
            public String getDescription() { return "Echoes input"; }
            @Override
            public JsonNode getParameters() {
                ObjectNode params = JsonNodeFactory.instance.objectNode();
                params.put("type", "object");
                return params;
            }
            @Override
            public String execute(JsonNode arguments) {
                return "echo: " + arguments.toString();
            }
        };
        toolRegistry.register(echoTool);

        // 第一次响应：工具调用
        ToolCall toolCall = ToolCall.builder()
                .id("call_1")
                .type("function")
                .function(new ToolCall.FunctionCall("echo", "{\"msg\":\"test\"}"))
                .build();
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistantWithToolCalls(List.of(toolCall)), "tool_calls")
        ), null));

        // 第二次响应：文本
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant("I echoed your message."), "stop")
        ), null));

        AgentLoop loop = new AgentLoop(chatClient, toolRegistry, objectMapper, "test");
        ChatMessage result = loop.run("Echo test", null);

        assertEquals("I echoed your message.", result.getContent());
        assertEquals(2, callCount); // 两次 API 调用：工具调用 + 后续调用

        List<ChatMessage> history = loop.getConversationHistory();
        // system + user + assistant(含 tool_calls) + tool_result + assistant(文本)
        assertEquals(5, history.size());
        assertEquals("tool", history.get(3).getRole());
        assertEquals("call_1", history.get(3).getToolCallId());
    }

    @Test
    void run_listenerReceivesCallbacks() {
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant("Done"), "stop")
        ), null));

        List<String> events = new ArrayList<>();
        ToolCallListener listener = new ToolCallListener() {
            @Override
            public void onToolCallStarted(ToolCall call) { events.add("started:" + call.getId()); }
            @Override
            public void onToolCallCompleted(String toolName, String result) { events.add("completed:" + toolName); }
            @Override
            public void onToolCallFailed(String toolName, String error) { events.add("failed:" + toolName); }
        };

        AgentLoop loop = new AgentLoop(chatClient, toolRegistry, objectMapper, "test");
        loop.run("Hi", listener);

        assertTrue(events.isEmpty()); // 没有工具调用，没有事件
    }

    @Test
    void run_listenerReceivesToolCallEvents() {
        Tool echoTool = new Tool() {
            @Override public String getName() { return "echo"; }
            @Override public String getDescription() { return "Echoes"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) { return "result"; }
        };
        toolRegistry.register(echoTool);

        ToolCall toolCall = ToolCall.builder()
                .id("call_1").type("function")
                .function(new ToolCall.FunctionCall("echo", "{}"))
                .build();
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistantWithToolCalls(List.of(toolCall)), "tool_calls")
        ), null));
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant("Done"), "stop")
        ), null));

        List<String> events = new ArrayList<>();
        ToolCallListener listener = new ToolCallListener() {
            @Override public void onToolCallStarted(ToolCall call) { events.add("started:" + call.getId()); }
            @Override public void onToolCallCompleted(String name, String result) { events.add("completed:" + name + ":" + result); }
            @Override public void onToolCallFailed(String name, String error) { events.add("failed:" + name); }
        };

        AgentLoop loop = new AgentLoop(chatClient, toolRegistry, objectMapper, "test");
        loop.run("Echo", listener);

        assertEquals(2, events.size());
        assertEquals("started:call_1", events.get(0));
        assertEquals("completed:echo:result", events.get(1));
    }

    @Test
    void run_maxIterationsExceeded() {
        ToolCall toolCall = ToolCall.builder()
                .id("call_loop").type("function")
                .function(new ToolCall.FunctionCall("echo", "{}"))
                .build();

        // 持续返回 tool_calls（模拟死循环）
        for (int i = 0; i < 25; i++) {
            responses.add(new ChatResponse(null, List.of(
                    new ChatResponse.Choice(0, ChatMessage.assistantWithToolCalls(List.of(toolCall)), "tool_calls")
            ), null));
        }

        // 注册 echo 工具，避免执行失败
        toolRegistry.register(new Tool() {
            @Override public String getName() { return "echo"; }
            @Override public String getDescription() { return "Echoes"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) { return "ok"; }
        });

        AgentLoop loop = new AgentLoop(chatClient, toolRegistry, objectMapper, "test");
        assertThrows(AgentLoop.AgentLoopException.class, () -> loop.run("Loop", null));
    }

    @Test
    void run_toolExecutionFailure_endsWithText() {
        toolRegistry.register(new Tool() {
            @Override public String getName() { return "fail_tool"; }
            @Override public String getDescription() { return "Always fails"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) { throw new ToolExecutionException("boom"); }
        });

        ToolCall toolCall = ToolCall.builder()
                .id("call_1").type("function")
                .function(new ToolCall.FunctionCall("fail_tool", "{}"))
                .build();
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistantWithToolCalls(List.of(toolCall)), "tool_calls")
        ), null));
        responses.add(new ChatResponse(null, List.of(
                new ChatResponse.Choice(0, ChatMessage.assistant("Tool failed, but I'll help anyway."), "stop")
        ), null));

        AgentLoop loop = new AgentLoop(chatClient, toolRegistry, objectMapper, "test");
        ChatMessage result = loop.run("Try failing tool", null);

        assertEquals("Tool failed, but I'll help anyway.", result.getContent());

        // 错误结果应出现在历史中
        List<ChatMessage> history = loop.getConversationHistory();
        ChatMessage toolResult = history.get(3);
        assertEquals("tool", toolResult.getRole());
        assertTrue(toolResult.getContent().contains("Error:"));
    }
}
