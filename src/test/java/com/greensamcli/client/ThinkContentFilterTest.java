package com.greensamcli.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ThinkContentFilter} 内联 think 标签剥离测试。
 *
 * <p>重点覆盖标签<b>跨增量分裂</b>的场景——SSE 传输中
 * {@code <think>} / {@code </think>} 可能被拆到多个 delta 里。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
class ThinkContentFilterTest {

    /**
     * 依次喂入 deltas，返回 [reasoning 列表, content 列表]（含 flush 兜底）
     */
    private static List<List<String>> feed(String... deltas) {
        List<String> reasoning = new ArrayList<>();
        List<String> content = new ArrayList<>();
        ThinkContentFilter filter = new ThinkContentFilter();
        for (String delta : deltas) {
            filter.feed(delta, content::add, reasoning::add);
        }
        filter.flush(content::add, reasoning::add);
        return List.of(reasoning, content);
    }

    @Test
    void 完整标签_单个增量() {
        List<List<String>> r = feed("<think>思考中</think>你好！");
        assertEquals(List.of("思考中"), r.get(0));
        assertEquals(List.of("你好！"), r.get(1));
    }

    @Test
    void 标签跨增量分裂() {
        List<List<String>> r = feed("<th", "ink>思考", "过程</th", "ink>你好", "！");
        assertEquals(List.of("思考", "过程"), r.get(0));
        assertEquals(List.of("你好", "！"), r.get(1));
    }

    @Test
    void 普通正文_无标签直通() {
        List<List<String>> r = feed("你好", "，世界");
        assertEquals(List.of(), r.get(0));
        assertEquals(List.of("你好", "，世界"), r.get(1));
    }

    @Test
    void 正文中间出现字面think标签_不误伤() {
        // 只剥离"流开头"的思考段；正文中途的字面 <think> 原样透传
        List<List<String>> r = feed("先看 <think> 这个标签");
        assertEquals(List.of(), r.get(0));
        assertEquals(List.of("先看 <think> 这个标签"), r.get(1));
    }

    @Test
    void 思考段未闭合_flush兜底为思考() {
        List<List<String>> r = feed("<think>流被截断了");
        assertEquals(List.of("流被截断了"), r.get(0));
        assertEquals(List.of(), r.get(1));
    }

    @Test
    void 判定期流结束_缓冲按正文投放() {
        // "<thi" 凑不满开标签，flush 时不能吞掉
        List<List<String>> r = feed("<thi");
        assertEquals(List.of(), r.get(0));
        assertEquals(List.of("<thi"), r.get(1));
    }

    @Test
    void stripThinkBlock_剥离开头的思考段() {
        assertEquals("\n\n你好", ThinkContentFilter.stripThinkBlock("<think>思考</think>\n\n你好"));
        assertEquals("普通回答", ThinkContentFilter.stripThinkBlock("普通回答"));
        assertEquals("<think>未闭合", ThinkContentFilter.stripThinkBlock("<think>未闭合"));
    }
}
