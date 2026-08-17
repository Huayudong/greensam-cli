package com.greensamcli.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
import com.greensamcli.agent.ToolExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "编辑文件"工具——让 LLM 能够对文件做精确的局部字符串替换。
 *
 * <p>当用户说"把 Main.java 里的 {@code foo()} 改成 {@code bar()}"时，LLM 会选择调用此工具，
 * 自动构造参数 {@code {"path": "...", "old_string": "foo()", "new_string": "bar()"}}。</p>
 *
 * <p><b>与 {@link WriteFileTool} 的区别</b>：write_file 是整体覆盖（create-or-overwrite），
 * 适合生成新文件或大段重写；edit_file 是精确替换，只改动命中的片段——
 * 既省 token，又避免重写整个文件时手抖丢内容。两者语义不同，故分别独立成工具，
 * 让 LLM 凭各自清晰的 description 准确选择。</p>
 *
 * <p><b>匹配语义</b>：old_string 按字面量匹配（非正则）。默认要求 old_string 在文件中
 * <b>唯一命中</b>——若出现多次会拒绝并提示提供更多上下文，逼 LLM 给出足够独特的片段。
 * 需要批量替换时传 {@code replace_all=true}。</p>
 *
 * <p><b>安全说明</b>：路径访问控制（如项目外编辑确认、敏感路径拦截）属横切关注点，
 * 由上层权限系统统一负责，不在工具层掺入——与 {@link WriteFileTool} 一致。
 * 工具内自包含护栏：拒绝空 old_string、拒绝 old==new 的无意义调用、文件大小上限。</p>
 *
 * @author Macro Ray
 * @since 2026-08-13
 */
@Slf4j
public class EditFileTool extends AbstractTool<EditFileTool.Args> {

    /**
     * 可编辑文件的大小上限（UTF-8 字节）。
     * <p>5MB 对常规代码 / 文档绰绰有余；超过即拒，避免把超大文件整体读入内存。
     */
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    public EditFileTool() {
        super(Args.class);
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "通过精确字符串替换编辑文件（old_string → new_string，非正则）。"
                + "默认要求 old_string 在文件中唯一命中，需批量替换时传 replace_all=true；"
                + "局部修改优先用本工具，而非整体重写的 write_file。";
    }

    /**
     * 参数声明：path / old_string / new_string 必填，replace_all 可选。
     * 参数 Schema 由 {@link AbstractTool} 依据此 record 自动生成。
     */
    public record Args(
            @Param(value = "要编辑的文件路径（绝对或相对路径）", required = true) String path,
            @Param(value = "要查找的精确文本（非正则）。除非 replace_all=true，必须在文件中唯一", required = true)
            @JsonProperty("old_string") String oldString,
            @Param(value = "替换后的文本，传空字符串可删除匹配内容", required = true)
            @JsonProperty("new_string") String newString,
            @Param("为 true 时替换所有出现位置，默认 false")
            @JsonProperty("replace_all") Boolean replaceAll) {

        public Args {
            // new_string 允许缺失（删除场景），容错为空串；replace_all 缺省为 false
            if (newString == null) {
                newString = "";
            }
            if (replaceAll == null) {
                replaceAll = false;
            }
        }
    }

    /**
     * 执行精确字符串替换。
     *
     * <p>执行流程：参数绑定（含默认值）→ 校验 → 读取全文 → 计数 old_string →
     * 唯一性判定 → 替换 → 写回 → 记录变更日志。</p>
     *
     * @param args 已绑定的参数 record，new_string / replace_all 已兜底默认值
     * @return 替换结果描述：替换次数 + 首处命中行号
     * @throws ToolExecutionException 目标不存在/是目录、old_string 为空或 old==new、未命中或不唯一、写回失败时抛出
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        String pathStr = args.path();
        Path path = Paths.get(pathStr);

        String oldString = args.oldString();
        String newString = args.newString();
        boolean replaceAll = args.replaceAll();

        // 前置校验
        validateEditRequest(oldString, newString, path);

        // 读取全文
        String content = readForEdit(path);

        // 统计命中次数（字面量、非重叠）
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            throw new ToolExecutionException("未在文件中找到 old_string: " + pathStr);
        }
        if (occurrences > 1 && !replaceAll) {
            throw new ToolExecutionException(
                    "old_string 不唯一（出现 " + occurrences + " 次）: " + pathStr
                            + "。请提供更多上下文，或设置 replace_all=true。");
        }

        // 替换前记录首处命中行号（按原始文件统计，1-based）
        int firstLine = lineNumberAt(content, oldString);

        // 执行替换：replaceAll 用字面量全量替换；单次用 Pattern.quote 锚定字面量替换首处
        String updated = replaceAll
                ? content.replace(oldString, newString)
                : content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));

        writeBack(path, updated);

        log.info("已编辑文件: path={}, replacements={}, firstLine={}, replaceAll={}",
                path, replaceAll ? occurrences : 1, firstLine, replaceAll);

        int applied = replaceAll ? occurrences : 1;
        return "已编辑 " + pathStr + "（替换 " + applied
                + " 处，首处命中位于第 " + firstLine + " 行）";
    }

    /**
     * 编辑请求的前置校验：old_string 非空、old!=new、目标存在且为普通文件、文件未超限。
     */
    private void validateEditRequest(String oldString, String newString, Path path) throws ToolExecutionException {
        if (oldString.isEmpty()) {
            throw new ToolExecutionException("old_string 不能为空");
        }
        if (oldString.equals(newString)) {
            throw new ToolExecutionException("old_string 与 new_string 相同，没有需要修改的内容");
        }
        if (!Files.exists(path)) {
            throw new ToolExecutionException("文件不存在: " + path);
        }
        if (Files.isDirectory(path)) {
            throw new ToolExecutionException("目标路径是目录: " + path);
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new ToolExecutionException(
                        "文件过大无法编辑: " + Files.size(path) + " 字节，上限 " + MAX_FILE_BYTES + " 字节");
            }
        } catch (IOException e) {
            throw new ToolExecutionException("获取文件大小失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取文件全文（UTF-8）。仅在校验通过后调用，大小已有上限保护。
     */
    private String readForEdit(Path path) throws ToolExecutionException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 写回替换后的内容（创建或截断覆盖）。
     */
    private void writeBack(Path path, String updated) throws ToolExecutionException {
        try {
            Files.writeString(path, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("写入文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 统计字面子串的非重叠出现次数。用 indexOf 逐段推进，避免正则特殊字符干扰。
     */
    private static int countOccurrences(String text, String substr) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substr, idx)) != -1) {
            count++;
            idx += substr.length();
        }
        return count;
    }

    /**
     * 计算 {@code substr} 在 {@code text} 中首次出现的行号（1-based）。
     * 调用前已确保 substr 存在。
     */
    private static int lineNumberAt(String text, String substr) {
        int charIdx = text.indexOf(substr);
        int line = 1;
        for (int i = 0; i < charIdx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
