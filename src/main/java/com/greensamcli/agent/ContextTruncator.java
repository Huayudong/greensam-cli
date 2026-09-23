package com.greensamcli.agent;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.utils.ContextTokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文截断器——发送 LLM 前把超限的对话历史砍回阈值以内。
 *
 * <p>截断单位是「完整对话轮」（从一条 user 消息到下一条 user 消息之前），
 * 而不是按单条消息删除。原因：OpenAI 兼容协议要求 assistant 消息的每个
 * tool_call 必须紧跟对应的 tool 结果消息——如果恰好从 assistant（含
 * tool_calls）与其 tool 结果之间切开，历史里就会留下孤儿 tool 消息或
 * 没有结果的 tool_call，多数兼容端点会直接返回 400。</p>
 *
 * <p>策略（与 roadmap 2.4 契约一致）：</p>
 * <ul>
 *   <li>估算不超过阈值时原样返回——恰好阈值不截断；</li>
 *   <li>超限时从最旧的完整轮开始逐轮丢弃，直到回落阈值以内；</li>
 *   <li>永远保住 index 0 的 system 消息与最后一轮对话——最后一轮是
 *       当前任务的上下文，丢了会答非所问；</li>
 *   <li>只剩最后一轮仍超限时不再丢弃（宁可带着超限风险请求，也不丢当前任务上下文）。</li>
 * </ul>
 *
 * <p>本类是无状态纯函数：输入历史列表与阈值，返回截断结果，不原地修改入参。</p>
 *
 * @author Macro Ray
 * @since 2026-09-18
 */
public final class ContextTruncator {

    /**
     * system 消息的 role 标识（与 {@link ChatMessage#system} 保持一致）
     */
    private static final String ROLE_SYSTEM = "system";
    /**
     * user 消息的 role 标识，作为对话轮的起点边界
     */
    private static final String ROLE_USER = "user";

    private ContextTruncator() {
    }

    /**
     * 截断结果：保留的消息列表与丢弃的总条数。
     *
     * @param keptMessages 截断后保留的消息（未截断时即原列表）
     * @param droppedCount 丢弃的最旧消息总条数；0 表示未发生截断
     */
    public record TruncateResult(List<ChatMessage> keptMessages, int droppedCount) {
    }

    /**
     * 按阈值截断对话历史。
     *
     * @param history   完整对话历史（index 0 应为 system 消息）；null 视为空历史
     * @param maxTokens 上下文 token 上限
     * @return 截断结果；未超限或无可安全丢弃的轮次时 droppedCount 为 0
     */
    public static TruncateResult truncate(List<ChatMessage> history, long maxTokens) {
        if (history == null || history.isEmpty()) {
            return new TruncateResult(history, 0);
        }
        if (ContextTokenEstimator.estimateMessages(history) <= maxTokens) {
            return new TruncateResult(history, 0);
        }

        List<ChatMessage> kept = new ArrayList<>(history);
        int dropped = 0;
        // 逐轮丢弃最旧对话，直到回落阈值或只剩最后一轮（不可再删）
        while (ContextTokenEstimator.estimateMessages(kept) > maxTokens) {
            // 第一轮的起点：第一条非 system 的 user 消息（正常对话中即 index 1）
            int firstRoundStart = findNextUserIndex(kept, firstNonSystemIndex(kept));
            if (firstRoundStart < 0) {
                break;
            }
            // 第二轮的起点：即第一轮的结束边界（不含）
            int secondRoundStart = findNextUserIndex(kept, firstRoundStart + 1);
            if (secondRoundStart < 0) {
                // 只剩最后一轮：保住当前任务上下文，停止丢弃
                break;
            }
            kept.subList(firstRoundStart, secondRoundStart).clear();
            dropped += secondRoundStart - firstRoundStart;
        }
        return new TruncateResult(kept, dropped);
    }

    /**
     * 找到第一个非 system 消息的下标；历史里只有 system（或为空）时返回列表长度。
     * <p>正常对话中 system 恒为 index 0，此方法保证 system 保护不依赖位置假设。</p>
     */
    private static int firstNonSystemIndex(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!ROLE_SYSTEM.equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return messages.size();
    }

    /**
     * 从 fromIndex（含）起找下一条 user 消息的下标；找不到返回 -1
     */
    private static int findNextUserIndex(List<ChatMessage> messages, int fromIndex) {
        for (int i = fromIndex; i < messages.size(); i++) {
            if (ROLE_USER.equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }
}
