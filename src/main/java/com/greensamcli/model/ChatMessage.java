package com.greensamcli.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 与 LLM API 交互的基本消息单元。
 *
 * <p>一条消息由 role（角色）和 content（内容）组成，不同角色的含义：</p>
 * <ul>
 *   <li>{@code system} — 系统提示词，设定 AI 的行为规则（只发送一次，贯穿整个对话）</li>
 *   <li>{@code user} — 用户输入</li>
 *   <li>{@code assistant} — LLM 的回复，可能是纯文本，也可能包含 tool_calls</li>
 *   <li>{@code tool} — 工具执行结果，回传给 LLM 让它继续推理</li>
 * </ul>
 *
 * <p>对应 OpenAI API 的消息格式：
 * <a href="https://platform.openai.com/docs/api-reference/chat/create#chat-create-messages">
 *   https://platform.openai.com/docs/api-reference/chat/create</a></p>
 *
 * <p>设计说明：同一个类用不同字段组合表示不同角色的消息。
 * 不是所有字段都会同时使用，例如 tool_calls 只有 assistant 消息才有，
 * tool_call_id 只有 tool 消息才有。
 * {@code @JsonInclude(NON_NULL)} 确保 null 字段不出现在 JSON 中。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessage {

    /** 消息角色：system / user / assistant / tool */
    private String role;

    /** 消息文本内容。当 assistant 消息包含 tool_calls 时，content 可能为 null */
    private String content;

    /**
     * LLM 请求调用的工具列表。仅 role="assistant" 时使用。
     * 当 LLM 决定调用工具时，响应中会包含这个字段而非 content。
     */
    @JsonProperty("tool_calls")
    private List<ToolCall> toolCalls;

    /**
     * 工具调用结果的唯一标识。仅 role="tool" 时使用。
     * 用于将工具结果与 LLM 发起的 tool_call 一一对应。
     * 例如 LLM 返回 tool_call id="call_abc"，工具执行结果也要带上 tool_call_id="call_abc"。
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;

    /** 工具名称。仅 role="tool" 时使用，标识这个结果来自哪个工具 */
    private String name;

    /**
     * 创建系统提示词消息。
     * 系统提示词在整个对话中只发送一次，放在 messages 数组的最前面，
     * 用于设定 AI 的角色、行为规则和可用能力。
     */
    public static ChatMessage system(String content) {
        return ChatMessage.builder()
                .role("system")
                .content(content)
                .build();
    }

    /** 创建用户消息，即用户在终端输入的内容 */
    public static ChatMessage user(String content) {
        return ChatMessage.builder()
                .role("user")
                .content(content)
                .build();
    }

    /** 创建纯文本的 assistant 回复（不包含工具调用） */
    public static ChatMessage assistant(String content) {
        return ChatMessage.builder()
                .role("assistant")
                .content(content)
                .build();
    }

    /**
     * 创建包含工具调用的 assistant 消息。
     * 当 LLM 决定需要调用工具时，返回的 assistant 消息不包含 content，
     * 而是包含 tool_calls 列表，表示"请帮我执行这些工具"。
     */
    public static ChatMessage assistantWithToolCalls(List<ToolCall> toolCalls) {
        return ChatMessage.builder()
                .role("assistant")
                .toolCalls(toolCalls)
                .build();
    }

    /**
     * 创建工具执行结果消息。
     * 每个工具执行完成后，结果会以 role="tool" 的消息追加到对话历史，
     * 然后把完整历史重新发送给 LLM，让 LLM 基于结果继续推理。
     *
     * @param toolCallId 与 LLM 发起的 tool_call 的 id 对应，用于匹配请求和结果
     * @param name       工具名称，如 "read_file"
     * @param content    工具执行返回的文本结果
     */
    public static ChatMessage toolResult(String toolCallId, String name, String content) {
        return ChatMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .name(name)
                .content(content)
                .build();
    }
}
