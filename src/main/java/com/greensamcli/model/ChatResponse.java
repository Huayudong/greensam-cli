package com.greensamcli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI Chat Completions API 的响应体。
 *
 * <p>API 返回的 JSON 结构：</p>
 *
 * <pre>{@code
 * {
 *   "id": "chatcmpl-xxxxx",
 *   "choices": [{
 *     "index": 0,
 *     "message": {
 *       "role": "assistant",
 *       "content": "文件内容是..." 或 null（当有 tool_calls 时）,
 *       "tool_calls": [...] 或 null
 *     },
 *     "finish_reason": "stop" 或 "tool_calls"
 *   }],
 *   "usage": {
 *     "prompt_tokens": 100,
 *     "completion_tokens": 50,
 *     "total_tokens": 150
 *   }
 * }
 * }</pre>
 *
 * <p>{@code finish_reason} 的含义：</p>
 * <ul>
 *   <li>{@code "stop"} — LLM 生成完毕，返回了最终的文本回复（Agent Loop 在此结束）</li>
 *   <li>{@code "tool_calls"} — LLM 请求调用工具（Agent Loop 继续执行工具并重新发送）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatResponse {

    private String id;
    /**
     * API 返回的候选回复列表，通常只有一个 choice
     */
    private List<Choice> choices;
    /**
     * 本次请求的 token 使用统计
     */
    private Usage usage;

    /**
     * 单个候选回复
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private int index;
        /**
         * assistant 的回复消息，可能包含文本内容或 tool_calls
         */
        private ChatMessage message;
        /**
         * 结束原因："stop"（文本回复）或 "tool_calls"（需要执行工具）
         */
        private String finishReason;
    }

    /**
     * Token 使用统计，可用于成本计算和上下文窗口管理
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
    }

    /**
     * 快捷方法：获取第一个 choice 的 assistant 消息
     */
    public ChatMessage getAssistantMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.get(0).getMessage();
    }

    /**
     * 快捷方法：判断 LLM 是否在请求调用工具
     */
    public boolean hasToolCalls() {
        ChatMessage msg = getAssistantMessage();
        return msg != null && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
    }
}
