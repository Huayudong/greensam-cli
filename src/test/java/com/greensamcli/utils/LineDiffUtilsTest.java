package com.greensamcli.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LineDiffUtils 行级 diff 测试：增/删/改识别、无差异、新文件、上下文聚合、
 * 超长截断、超限退化统计。
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
class LineDiffUtilsTest {

    @Test
    void 无差异_返回空串() {
        assertEquals("", LineDiffUtils.unifiedDiff("a\nb\n", "a\nb\n"));
        assertEquals("", LineDiffUtils.unifiedDiff(null, ""));
    }

    @Test
    void 新文件_全部为新增行() {
        String diff = LineDiffUtils.unifiedDiff(null, "hello\nworld");
        assertTrue(diff.contains("新建文件") == false, "新文件提示由调用方负责，diff 只输出差异");
        assertTrue(diff.contains("+ hello"), diff);
        assertTrue(diff.contains("+ world"), diff);
        assertFalse(diff.contains("- "), diff);
    }

    @Test
    void 内容删除_全部为删除行() {
        String diff = LineDiffUtils.unifiedDiff("only-line\n", null);
        assertTrue(diff.contains("- only-line"), diff);
    }

    @Test
    void 空串与null等价_均为零行() {
        assertEquals("", LineDiffUtils.unifiedDiff("", ""));
        String diff = LineDiffUtils.unifiedDiff("", "x\n");
        assertTrue(diff.contains("+ x"), diff);
        assertFalse(diff.lines().anyMatch(line -> line.startsWith("- ")), diff);
    }

    @Test
    void 单行修改_同时出现删除与新增() {
        String diff = LineDiffUtils.unifiedDiff("keep\nold-value\nkeep2", "keep\nnew-value\nkeep2");

        assertTrue(diff.contains("keep"), "上下文行应保留: " + diff);
        assertTrue(diff.contains("- old-value"), diff);
        assertTrue(diff.contains("+ new-value"), diff);
        assertTrue(diff.contains("keep2"), diff);
        // 变更统计头部
        assertTrue(diff.contains("变更统计：-3 行 / +3 行"), diff);
    }

    @Test
    void 多处修改_拆分为多个hunk() {
        String oldText = "a\nb\nX\nc\nd\ne\nf\ng\nY\nh";
        String newText = "a\nb\nX2\nc\nd\ne\nf\ng\nY2\nh";
        String diff = LineDiffUtils.unifiedDiff(oldText, newText);

        assertEquals(2, diff.split("@@", -1).length - 1, "应有两个 hunk 头: " + diff);
        assertTrue(diff.contains("- X") && diff.contains("+ X2"), diff);
        assertTrue(diff.contains("- Y") && diff.contains("+ Y2"), diff);
    }

    @Test
    void 超长差异_截断并标注省略行数() {
        StringBuilder newText = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            newText.append("line-").append(i).append('\n');
        }
        String diff = LineDiffUtils.unifiedDiff("", newText.toString());

        assertTrue(diff.contains("已省略"), diff);
        assertTrue(diff.contains("变更统计：-0 行 / +121 行"), diff);
    }

    @Test
    void 超过计算上限_退化为纯统计() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 6000; i++) {
            big.append("x").append(i).append('\n');
        }
        String diff = LineDiffUtils.unifiedDiff(big.toString(), "new content\n");

        assertTrue(diff.contains("仅显示统计"), diff);
        assertFalse(diff.contains("+ new content"), "超限后不应输出逐行差异: " + diff);
    }

    @Test
    void 行尾差异归一_CRLF与LF视为相同() {
        assertEquals("", LineDiffUtils.unifiedDiff("a\r\nb\r\n", "a\nb\n"));
    }
}
