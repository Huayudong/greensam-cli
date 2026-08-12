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

/**
 * "写入文件"工具——让 LLM 能够创建或覆盖本地文件系统的文件内容。
 *
 * <p>当用户说"把这段代码写进 src/Main.java"时，LLM 会选择调用此工具，
 * 自动构造参数 {@code {"path": "src/Main.java", "content": "..."}}。</p>
 *
 * <p><b>写入语义</b>：创建或覆盖（create-or-overwrite）。目标文件不存在则新建，
 * 已存在则整体截断覆盖。父目录缺失时会自动创建，避免 LLM 额外发起目录创建调用。</p>
 *
 * <p><b>安全说明</b>：本工具对写入路径不做硬性限制（支持绝对路径与相对路径，
 * 相对路径以 JVM 工作目录为基准，与 {@link ReadFileTool} / {@link ListFilesTool} 一致）。
 * 路径访问控制（如项目外写入确认、敏感路径拦截）属于横切关注点，
 * 应由上层权限系统统一负责，不在工具层掺入——详见 README「后续完善方向-权限系统」。</p>
 *
 * <p>安全措施：单次写入字节数不得超过 {@link #MAX_WRITE_FILE_BYTES}，超限即拒，
 * 避免磁盘灌满与误覆盖。</p>
 *
 * @author Ma Chengrui
 * @since 2026-08-11
 */
@Slf4j
public class WriteFileTool implements Tool {

    /**
     * 单次写入字节数上限。
     * <p>
     * 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
     */
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "Create or overwrite a file with the given content. "
                + "Supports absolute or relative paths. Max 5MB per write.";
    }

    /**
     * 返回参数的 JSON Schema，告诉 LLM 这个工具需要 "path" 和 "content" 两个参数。
     * LLM 看到这个定义后就知道应该传什么参数。
     */
    @Override
    public JsonNode getParameters() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode properties = JsonNodeFactory.instance.objectNode();

        // "path" 参数：字符串类型，必填——目标文件路径（绝对或相对）
        ObjectNode pathProp = JsonNodeFactory.instance.objectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "Absolute or relative path of the file to create or overwrite");
        properties.set("path", pathProp);

        // "content" 参数：字符串类型，必填——待写入的文本内容
        ObjectNode contentProp = JsonNodeFactory.instance.objectNode();
        contentProp.put("type", "string");
        contentProp.put("description", "Text content to write (UTF-8). Use empty string to clear the file.");
        properties.set("content", contentProp);

        params.set("properties", properties);

        params.putArray("required").add("path").add("content");
        return params;
    }

    /**
     * 执行文件写入。
     *
     * <p>执行流程：解析参数 → 目录冲突校验 → 内容大小校验 → 自动创建父目录 →
     * 写入文件（创建或截断覆盖）→ 记录变更日志。</p>
     *
     * @param arguments LLM 构造的参数，包含 {@code path} 与 {@code content}
     * @return 写入结果描述：新建返回 {@code "Created <path> (<N> bytes)"}，
     *         覆盖返回 {@code "Overwrote <path> (<N> bytes, was <oldN> bytes)"}
     * @throws ToolExecutionException 目标路径是目录、内容超限或写入失败时抛出
     */
    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        String pathStr = arguments.get("path").asText();
        Path path = Paths.get(pathStr);

        // 前置检查：目标路径若已是目录，禁止写入（避免误覆盖目录）
        if (Files.isDirectory(path)) {
            throw new ToolExecutionException("Path is a directory: " + pathStr);
        }

        // 读取内容，null 容错为空串（支持清空文件场景）
        JsonNode contentNode = arguments.get("content");
        String content = contentNode == null || contentNode.isNull() ? "" : contentNode.asText();

        // 大小校验：UTF-8 字节数超限即拒
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_WRITE_FILE_BYTES) {
            throw new ToolExecutionException(
                    "Content too large: " + contentBytes + " bytes, max " + MAX_WRITE_FILE_BYTES);
        }

        // 写入前记录是否已存在，用于区分新建/覆盖，并在覆盖时保留旧字节数
        boolean existed = Files.exists(path);
        long oldBytes = existed ? sizeOf(path) : 0L;

        try {
            // 父目录缺失时自动创建（getParent 对单段相对路径返回 null，需防御）
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to write file: " + e.getMessage(), e);
        }

        // 关键写操作留 INFO 日志：路径、操作类型、新旧字节数（AGENTS.md 5.4）
        if (existed) {
            log.info("Overwrote file: path={}, newBytes={}, oldBytes={}", path, contentBytes, oldBytes);
        } else {
            log.info("Created file: path={}, bytes={}", path, contentBytes);
        }

        return existed
                ? "Overwrote " + pathStr + " (" + contentBytes + " bytes, was " + oldBytes + " bytes)"
                : "Created " + pathStr + " (" + contentBytes + " bytes)";
    }

    /**
     * 读取文件大小，失败时按 0 记录（仅用于覆盖场景的日志/返回值，不阻断主流程）。
     */
    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("Failed to read old file size, treating as 0: path={}, err={}", path, e.getMessage());
            return 0L;
        }
    }
}
