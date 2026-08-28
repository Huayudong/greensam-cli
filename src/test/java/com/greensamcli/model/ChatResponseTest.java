package com.greensamcli.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ChatResponse} Jackson 反序列化测试。
 *
 * <p>API 以 snake_case 传输（{@code prompt_tokens}、{@code finish_reason}），
 * 字段若漏标 {@code @JsonProperty} 会静默解析为 0 / null——
 * 本测试锁定映射关系，防止回归（Usage 漏标曾导致 token 统计恒为 0）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
class ChatResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usage与finishReason_snakeCase字段正确映射() throws Exception {
        String json = """
                {
                  "id": "chatcmpl-x",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "你好"},
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 50,
                    "total_tokens": 150
                  }
                }
                """;

        ChatResponse response = mapper.readValue(json, ChatResponse.class);

        assertEquals(100, response.getUsage().getPromptTokens());
        assertEquals(50, response.getUsage().getCompletionTokens());
        assertEquals(150, response.getUsage().getTotalTokens());
        assertEquals("stop", response.getChoices().get(0).getFinishReason());
    }
}
