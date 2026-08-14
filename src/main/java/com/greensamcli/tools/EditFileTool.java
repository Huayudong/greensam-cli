package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Tool;
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
public class EditFileTool implements Tool {

    /**
     * 可编辑文件的大小上限（UTF-8 字节）。
     * <p>5MB 对常规代码 / 文档绰绰有余；超过即拒，避免把超大文件整体读入内存。
     */
    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "Edit a file by replacing an exact string (old_string -> new_string). "
                + "old_string must be unique in the file unless replace_all is true; "
                + "prefer this over write_file for targeted changes.";
    }

    /**
     * 参数 Schema：path / old_string / new_string 必填，replace_all 可选。
     * 三个核心参数类型清晰、无互斥关系，便于 LLM 准确构造。
     */
    @Override
    public JsonNode getParameters() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode properties = JsonNodeFactory.instance.objectNode();

        ObjectNode pathProp = JsonNodeFactory.instance.objectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "Absolute or relative path of the file to edit");
        properties.set("path", pathProp);

        ObjectNode oldProp = JsonNodeFactory.instance.objectNode();
        oldProp.put("type", "string");
        oldProp.put("description", "The exact text to find. Must be unique unless replace_all is true.");
        properties.set("old_string", oldProp);

        ObjectNode newProp = JsonNodeFactory.instance.objectNode();
        newProp.put("type", "string");
        newProp.put("description", "The replacement text. Use empty string to delete the match.");
        properties.set("new_string", newProp);

        ObjectNode replaceAllProp = JsonNodeFactory.instance.objectNode();
        replaceAllProp.put("type", "boolean");
        replaceAllProp.put("description", "If true, replace every occurrence. Default false.");
        properties.set("replace_all", replaceAllProp);

        params.set("properties", properties);
        params.putArray("required").add("path").add("old_string").add("new_string");
        return params;
    }

    /**
     * 执行精确字符串替换。
     *
     * <p>执行流程：解析参数 → 校验 → 读取全文 → 计数 old_string →
     * 唯一性判定 → 替换 → 写回 → 记录变更日志。</p>
     *
     * @param arguments 含 {@code path} / {@code old_string} / {@code new_string}，可选 {@code replace_all}
     * @return 替换结果描述：替换次数 + 首处命中行号
     * @throws ToolExecutionException 目标不存在/是目录、old_string 为空或 old==new、未命中或不唯一、写回失败时抛出
     */
    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        String pathStr = arguments.get("path").asText();
        Path path = Paths.get(pathStr);

        String oldString = arguments.get("old_string").asText();
        // new_string 允许为空（删除场景），null 容错为空串
        JsonNode newNode = arguments.get("new_string");
        String newString = newNode == null || newNode.isNull() ? "" : newNode.asText();
        boolean replaceAll = arguments.has("replace_all") && arguments.get("replace_all").asBoolean();

        // 前置校验
        validateEditRequest(oldString, newString, path);

        // 读取全文
        String content = readForEdit(path);

        // 统计命中次数（字面量、非重叠）
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            throw new ToolExecutionException("old_string not found in: " + pathStr);
        }
        if (occurrences > 1 && !replaceAll) {
            throw new ToolExecutionException(
                    "old_string is not unique (" + occurrences + " occurrences) in: " + pathStr
                            + ". Provide more context or set replace_all=true.");
        }

        // 替换前记录首处命中行号（按原始文件统计，1-based）
        int firstLine = lineNumberAt(content, oldString);

        // 执行替换：replaceAll 用字面量全量替换；单次用 Pattern.quote 锚定字面量替换首处
        String updated = replaceAll
                ? content.replace(oldString, newString)
                : content.replaceFirst(Pattern.quote(oldString), Matcher.quoteReplacement(newString));

        writeBack(path, updated);

        log.info("Edited file: path={}, replacements={}, firstLine={}, replaceAll={}",
                path, replaceAll ? occurrences : 1, firstLine, replaceAll);

        int applied = replaceAll ? occurrences : 1;
        return "Edited " + pathStr + " (replaced " + applied
                + " occurrence(s), first match at line " + firstLine + ")";
    }

    /**
     * 编辑请求的前置校验：old_string 非空、old!=new、目标存在且为普通文件、文件未超限。
     */
    private void validateEditRequest(String oldString, String newString, Path path) throws ToolExecutionException {
        if (oldString.isEmpty()) {
            throw new ToolExecutionException("old_string must not be empty");
        }
        if (oldString.equals(newString)) {
            throw new ToolExecutionException("old_string and new_string are identical; nothing to change");
        }
        if (!Files.exists(path)) {
            throw new ToolExecutionException("File not found: " + path);
        }
        if (Files.isDirectory(path)) {
            throw new ToolExecutionException("Path is a directory: " + path);
        }
        try {
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw new ToolExecutionException(
                        "File too large to edit: " + Files.size(path) + " bytes, max " + MAX_FILE_BYTES);
            }
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to stat file: " + e.getMessage(), e);
        }
    }

    /**
     * 读取文件全文（UTF-8）。仅在校验通过后调用，大小已有上限保护。
     */
    private String readForEdit(Path path) throws ToolExecutionException {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to read file: " + e.getMessage(), e);
        }
    }

    /**
     * 写回替换后的内容（创建或截断覆盖）。
     */
    private void writeBack(Path path, String updated) throws ToolExecutionException {
        try {
            Files.writeString(path, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to write file: " + e.getMessage(), e);
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
