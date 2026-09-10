package com.greensamcli.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级文本 diff 工具——为审批展示生成「旧内容 → 新内容」的差异摘要。
 *
 * <p>基于最长公共子序列（LCS）的行级比较，输出带 {@code -/+} 前缀与
 * 3 行上下文的 hunk 段落；任一侧超过 {@value #MAX_DIFF_INPUT_LINES} 行时
 * 退化为统计摘要（避免大文件下 O(n*m) 的计算与超长输出撑爆审批界面）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
public final class LineDiffUtils {

    /**
     * 参与 LCS 计算的单侧最大行数，超过则不计算差异只输出统计
     */
    private static final int MAX_DIFF_INPUT_LINES = 5000;
    /**
     * 每个 hunk 两侧携带的上下文行数
     */
    private static final int CONTEXT_LINES = 3;
    /**
     * 两个 hunk 之间相同行数不超过该值时合并为一个 hunk
     */
    private static final int HUNK_MERGE_UNCHANGED = 6;
    /**
     * 输出总行数上限，超出截断并标注
     */
    private static final int MAX_OUTPUT_LINES = 80;

    private LineDiffUtils() {
    }

    /**
     * 生成行级差异文本。
     *
     * @param oldText 旧内容；null 视为空（新文件场景）
     * @param newText 新内容；null 视为空（删除场景）
     * @return 差异文本；无差异时返回空串
     */
    public static String unifiedDiff(String oldText, String newText) {
        List<String> oldLines = splitLines(oldText);
        List<String> newLines = splitLines(newText);

        if (oldLines.equals(newLines)) {
            return "";
        }

        StringBuilder summary = new StringBuilder();
        summary.append("变更统计：-").append(oldLines.size()).append(" 行 / +")
                .append(newLines.size()).append(" 行\n");

        if (oldLines.size() > MAX_DIFF_INPUT_LINES || newLines.size() > MAX_DIFF_INPUT_LINES) {
            summary.append("（内容过长，仅显示统计，完整内容请直接查看文件）");
            return summary.toString();
        }

        List<Op> ops = buildOps(oldLines, newLines);
        List<String> diffLines = renderHunks(ops, oldLines, newLines);

        int total = diffLines.size();
        int shown = Math.min(total, MAX_OUTPUT_LINES);
        for (int i = 0; i < shown; i++) {
            summary.append(diffLines.get(i)).append('\n');
        }
        if (total > shown) {
            summary.append("…（差异过长，已省略 ").append(total - shown).append(" 行）");
        }
        return summary.toString().stripTrailing();
    }

    /**
     * 单个差异操作：type 为 ' '（相同）、'-'（旧内容删除）、'+'（新内容新增），
     * oldIndex / newIndex 是该操作在两侧行列表中的下标。
     */
    private record Op(char type, int oldIndex, int newIndex) {
    }

    /**
     * LCS 回溯出完整的操作序列。
     */
    private static List<Op> buildOps(List<String> oldLines, List<String> newLines) {
        int n = oldLines.size();
        int m = newLines.size();
        // lcs[i][j] = oldLines[i..] 与 newLines[j..] 的公共子序列长度
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Op> ops = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                ops.add(new Op(' ', i, j));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op('-', i, j));
                i++;
            } else {
                ops.add(new Op('+', i, j));
                j++;
            }
        }
        while (i < n) {
            ops.add(new Op('-', i, j));
            i++;
        }
        while (j < m) {
            ops.add(new Op('+', i, j));
            j++;
        }
        return ops;
    }

    /**
     * 把操作序列按「变更 + 前后各 3 行上下文」聚合成 hunk 段落。
     */
    private static List<String> renderHunks(List<Op> ops, List<String> oldLines, List<String> newLines) {
        List<String> result = new ArrayList<>();
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k).type() == ' ') {
                k++;
                continue;
            }
            // hunk 展示起点：第一个变更往前带上下文；终点：最后一个变更后的连续相同行
            // 一旦超过合并阈值就截断（后续再有变更会开启新的 hunk）
            int start = Math.max(0, k - CONTEXT_LINES);
            int end = k;
            int unchangedRun = 0;
            for (int t = k; t < ops.size(); t++) {
                if (ops.get(t).type() == ' ') {
                    unchangedRun++;
                    if (unchangedRun > HUNK_MERGE_UNCHANGED) {
                        break;
                    }
                } else {
                    unchangedRun = 0;
                    end = t;
                }
            }
            int stop = Math.min(ops.size(), end + CONTEXT_LINES + 1);

            result.add(String.format("@@ 旧第 %d 行 / 新第 %d 行 @@",
                    lineNoBefore(ops, start, '-'), lineNoBefore(ops, start, '+')));
            for (int t = start; t < stop; t++) {
                Op op = ops.get(t);
                String line = op.type() == '-' ? oldLines.get(op.oldIndex()) : newLines.get(op.newIndex());
                result.add(op.type() + " " + line);
            }
            k = stop;
        }
        return result;
    }

    /**
     * 某操作位置之前（含该位置）某一侧已消费的行数，即该侧的 1 起行号。
     */
    private static int lineNoBefore(List<Op> ops, int index, char side) {
        int count = 0;
        for (int t = 0; t < index; t++) {
            Op op = ops.get(t);
            if (op.type() == ' ' || op.type() == side) {
                count++;
            }
        }
        return count + 1;
    }

    /**
     * 按行拆分文本，兼容 \r\n 与 \n；null / 空串视为零行（新文件场景不产生幻影删除行）。
     */
    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return List.of(text.replace("\r\n", "\n").split("\n", -1));
    }
}
