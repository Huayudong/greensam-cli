package com.greensamcli.utils;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextTokenEstimator} 字符近似估算测试。
 *
 * <p>覆盖估算规则的三类输入（纯 ASCII / 纯 CJK / 混合）与
 * 消息级聚合（固定结构开销、tool_calls 计入、null 与空安全）。</p>
 *
 * @author Macro Ray
 * @since 2026-09-18
 */
class ContextTokenEstimatorTest {

    @Test
    void 纯ASCII_按4字符1token() {
        assertEquals(2, ContextTokenEstimator.estimateTokens("abcdefgh"));
    }

    @Test
    void ASCII不足4字符_向上取整() {
        assertEquals(1, ContextTokenEstimator.estimateTokens("abc"));
    }

    @Test
    void 纯中文_按1字1token() {
        assertEquals(4, ContextTokenEstimator.estimateTokens("你好世界"));
    }

    @Test
    void 中英混合_分段换算后相加() {
        // 3 个 ASCII 字符 → 1 token，2 个中文字 → 2 tokens，合计 3
        assertEquals(3, ContextTokenEstimator.estimateTokens("abc你好"));
    }

    @Test
    void 空文本与null_返回0() {
        assertEquals(0, ContextTokenEstimator.estimateTokens(null));
        assertEquals(0, ContextTokenEstimator.estimateTokens(""));
    }

    @Test
    void 估算_中文按1字1token_明显高于按字符数均摊() {
        // 保守高估的落点：100 个中文按 100 token 计，而非 100/4
        String chinese = "字".repeat(100);
        assertTrue(ContextTokenEstimator.estimateTokens(chinese) >= 100);
    }

    @Test
    void 消息列表_计入每条固定结构开销() {
        // 单条 user 消息：content "abcd" 1 token + 结构开销 8 = 9
        long estimated = ContextTokenEstimator.estimateMessages(List.of(ChatMessage.user("abcd")));
        assertEquals(9, estimated);
    }

    @Test
    void 消息列表_计入toolCalls函数名与参数长度() {
        // assistant 消息无 content：name "echo" 1 + arguments "{}" 1 + 结构开销 8 = 10
        ChatMessage message = ChatMessage.assistantWithToolCalls(List.of(
                ToolCall.builder().id("call_1").type("function")
                        .function(new ToolCall.FunctionCall("echo", "{}"))
                        .build()));
        assertEquals(10, ContextTokenEstimator.estimateMessages(List.of(message)));
    }

    @Test
    void 消息列表_空与null_返回0() {
        assertEquals(0, ContextTokenEstimator.estimateMessages(null));
        assertEquals(0, ContextTokenEstimator.estimateMessages(List.of()));
    }
}
