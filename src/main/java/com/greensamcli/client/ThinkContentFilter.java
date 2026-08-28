package com.greensamcli.client;

import java.util.function.Consumer;

/**
 * 内联 think 标签过滤器——把混在正文流里的思考段剥离出来。
 *
 * <p><b>为什么需要</b>：推理型模型对外暴露思考过程有两种 wire 格式——
 * 独立的 {@code delta.reasoning_content} 字段（DeepSeek、GLM 部分端点），
 * 以及<b>直接混在 content 里</b>的 {@code <think>...</think>} 标签（GLM 兼容端点常见）。
 * 后者若不处理，思考原文会带着标签一起显示给用户，并污染对话历史。</p>
 *
 * <p><b>难点</b>：SSE 是增量传输，{@code <think>} / {@code </think>} 标签本身
 * 可能被拆到多个 delta 里（如先收到 {@code "<th"} 再收到 {@code "ink>"}），
 * 必须用状态机跨增量识别：</p>
 *
 * <ol>
 *   <li>判定期：流开头先缓冲，凑齐 7 字符确认是否以 {@code <think>} 开头；
 *       一旦确认不是（{@code decided}），后续增量走快速路径原样透传；</li>
 *   <li>思考期：命中开标签后，内容交给 reasoning 消费者；
 *       为防闭合标签跨增量，每次只放行"末尾最长可能是 {@code </think>} 前缀"之外的部分；</li>
 *   <li>正文期：闭标签之后的内容全部交给 content 消费者。</li>
 * </ol>
 *
 * <p>单个实例对应一次响应流，非线程安全（SSE 在单线程内逐增量驱动）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
final class ThinkContentFilter {

    private static final String OPEN_TAG = "<think>";
    private static final String CLOSE_TAG = "</think>";

    /**
     * 判定期/思考期的跨增量缓冲
     */
    private final StringBuilder buffer = new StringBuilder();
    /**
     * 是否正处于 {@code <think>} 思考段内
     */
    private boolean inThink;
    /**
     * 是否已判定完毕（流不以开标签开头，或思考段已正常闭合），
     * 判定后不再缓冲，增量直接透传
     */
    private boolean decided;

    /**
     * 喂入一个正文增量，剥离结果分别投递给两个消费者。
     *
     * @param delta         本次收到的 content 增量（原样，可能含 think 标签片段）
     * @param contentSink   正文消费者（剥离后的真实回答内容）
     * @param reasoningSink 思考消费者（{@code <think>} 段内的内容）
     */
    void feed(String delta, Consumer<String> contentSink, Consumer<String> reasoningSink) {
        // 快速路径：已判定为普通正文流，零缓冲直通
        if (decided && !inThink) {
            contentSink.accept(delta);
            return;
        }
        buffer.append(delta);
        process(contentSink, reasoningSink);
    }

    /**
     * 流结束兜底：把缓冲里残留的内容投递出去
     * （思考段未闭合 → 残留算思考；判定期待凑不满标签 → 残留算正文）。
     */
    void flush(Consumer<String> contentSink, Consumer<String> reasoningSink) {
        if (buffer.length() == 0) {
            return;
        }
        if (inThink) {
            reasoningSink.accept(buffer.toString());
        } else {
            contentSink.accept(buffer.toString());
        }
        buffer.setLength(0);
    }

    /**
     * 状态机主循环：反复消费缓冲，直到需要更多增量才能推进为止
     */
    private void process(Consumer<String> contentSink, Consumer<String> reasoningSink) {
        while (true) {
            if (!inThink) {
                // 缓冲足以判定开标签
                if (buffer.length() >= OPEN_TAG.length()
                        && buffer.substring(0, OPEN_TAG.length()).equals(OPEN_TAG)) {
                    buffer.delete(0, OPEN_TAG.length());
                    inThink = true;
                    continue;
                }
                // 仍可能是开标签的前缀 → 等下一个增量凑齐再判定
                if (!decided && isPrefixOf(OPEN_TAG, buffer)) {
                    break;
                }
                // 不是 think 流（或 think 已结束）：全部是正文，之后零缓冲直通
                decided = true;
                if (buffer.length() > 0) {
                    contentSink.accept(buffer.toString());
                    buffer.setLength(0);
                }
                break;
            }

            // 思考段内：寻找闭合标签
            int closeIdx = buffer.indexOf(CLOSE_TAG);
            if (closeIdx >= 0) {
                if (closeIdx > 0) {
                    reasoningSink.accept(buffer.substring(0, closeIdx));
                }
                buffer.delete(0, closeIdx + CLOSE_TAG.length());
                inThink = false;
                decided = true;
                continue;
            }
            // 未找到闭合标签：保留末尾"可能是闭合标签前缀"的最长部分，
            // 其余先投递给 reasoning，防止闭合标签跨增量被截断误伤
            int keep = partialCloseSuffixLength();
            if (buffer.length() > keep) {
                reasoningSink.accept(buffer.substring(0, buffer.length() - keep));
                buffer.delete(0, buffer.length() - keep);
            }
            break;
        }
    }

    /**
     * 缓冲末尾与闭合标签前缀的最长匹配长度（0 表示无可疑后缀）
     */
    private int partialCloseSuffixLength() {
        int max = Math.min(CLOSE_TAG.length() - 1, buffer.length());
        for (int len = max; len > 0; len--) {
            String suffix = buffer.substring(buffer.length() - len);
            if (CLOSE_TAG.startsWith(suffix)) {
                return len;
            }
        }
        return 0;
    }

    /**
     * text 是否是 pattern 的真前缀（长度小于 pattern 且逐字符匹配）
     */
    private static boolean isPrefixOf(String pattern, CharSequence text) {
        return text.length() < pattern.length()
                && pattern.startsWith(text.toString());
    }

    /**
     * 同步响应的整段剥离：content 以 {@code <think>...</think>} 开头时去掉思考段。
     *
     * <p>仅处理"流开头"的思考段（与流式判定口径一致）；
     * 未闭合的思考段原样保留，避免误删真实内容。</p>
     *
     * @param content 原始 content
     * @return 剥离后的正文；无思考段时原样返回
     */
    static String stripThinkBlock(String content) {
        if (content == null || !content.startsWith(OPEN_TAG)) {
            return content;
        }
        int closeIdx = content.indexOf(CLOSE_TAG);
        if (closeIdx < 0) {
            return content;
        }
        return content.substring(closeIdx + CLOSE_TAG.length());
    }
}
