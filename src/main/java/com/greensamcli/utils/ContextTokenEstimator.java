package com.greensamcli.utils;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ToolCall;

import java.util.List;

/**
 * 上下文 token 估算器——用字符近似法估算消息历史的 token 占用。
 *
 * <p>不依赖任何 tokenizer 依赖，采用保守高估的近似规则：</p>
 * <ul>
 *   <li>非 ASCII 字符（中文、emoji 等）按 1 字 ≈ 1 token；</li>
 *   <li>ASCII 字符按 4 字符 ≈ 1 token，不足 4 字符向上取整；</li>
 *   <li>每条消息额外计入固定结构开销（role、tool_call_id 等 JSON 字段）。</li>
 * </ul>
 *
 * <p>估算偏高只会让截断提前发生（多丢几条旧消息），估算偏低则可能撑爆
 * 模型上下文窗口导致请求直接失败——两害相权，宁高勿低。</p>
 *
 * @author Macro Ray
 * @since 2026-09-18
 */
public final class ContextTokenEstimator {

    /**
     * ASCII 字符与 token 的换算比例：每 4 个 ASCII 字符约等于 1 个 token
     */
    private static final int ASCII_CHARS_PER_TOKEN = 4;
    /**
     * 每条消息的 JSON 结构开销（role、tool_call_id 等字段的保守估算）
     */
    private static final int PER_MESSAGE_OVERHEAD_TOKENS = 8;

    private ContextTokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 待估算文本；null 视为空串（返回 0）
     * @return 估算 token 数（向上取整，保守高估）
     */
    public static long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int asciiCount = 0;
        int nonAsciiCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                asciiCount++;
            } else {
                nonAsciiCount++;
            }
        }
        return (long) Math.ceil((double) asciiCount / ASCII_CHARS_PER_TOKEN) + nonAsciiCount;
    }

    /**
     * 估算整段消息历史的 token 数。
     *
     * <p>计入每条消息的 content 与 assistant 消息中 tool_calls 的
     * 函数名、arguments（JSON 字符串）长度，以及每条消息的固定结构开销。</p>
     *
     * @param messages 消息历史；null 视为空列表（返回 0）
     * @return 估算 token 总数
     */
    public static long estimateMessages(List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        long total = 0;
        for (ChatMessage message : messages) {
            total += PER_MESSAGE_OVERHEAD_TOKENS;
            total += estimateTokens(message.getContent());
            List<ToolCall> toolCalls = message.getToolCalls();
            if (toolCalls == null) {
                continue;
            }
            for (ToolCall toolCall : toolCalls) {
                ToolCall.FunctionCall function = toolCall.getFunction();
                if (function == null) {
                    continue;
                }
                total += estimateTokens(function.getName());
                total += estimateTokens(function.getArguments());
            }
        }
        return total;
    }
}
