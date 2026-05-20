package com.greensamcli.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 发送给 OpenAI Chat Completions API 的请求体。
 *
 * <p>对应 POST https://api.openai.com/v1/chat/completions 的请求 JSON。</p>
 *
 * <pre>{@code
 * {
 *   "model": "gpt-4o",
 *   "messages": [
 *     {"role": "system", "content": "你是一个助手"},
 *     {"role": "user", "content": "读取文件 /tmp/test.txt"}
 *   ],
 *   "tools": [
 *     {"type": "function", "function": {"name": "read_file", ...}}
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 确保 tools 为 null 时不出现在 JSON 中
 * （不带工具的普通对话不需要 tools 字段）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    /** 模型名称，如 "gpt-4o"、"gpt-4o-mini" */
    private String model;

    /** 对话消息列表，按时间顺序排列：system → user → assistant → tool → ... */
    private List<ChatMessage> messages;

    /** 可用工具定义列表。为 null 时不发送，LLM 只能生成纯文本回复 */
    private List<ToolDefinition> tools;
}
