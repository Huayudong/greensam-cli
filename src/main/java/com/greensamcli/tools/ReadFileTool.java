package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Tool;
import com.greensamcli.agent.ToolExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * "读取文件"工具——让 LLM 能够读取本地文件系统的文件内容。
 *
 * <p>当用户说"帮我看看 /tmp/test.txt 里的内容"时，LLM 会选择调用此工具，
 * 自动构造参数 {@code {"path": "/tmp/test.txt"}}。</p>
 *
 * <p>安全措施：超过 {@link #MAX_CHARS} 个字符的文件会被截断，
 * 防止将超大文件内容发送给 LLM（会消耗大量 token 且可能超出上下文窗口限制）。</p>
 */
public class ReadFileTool implements Tool {

    /**
     * 单次读取的最大字符数，超出部分截断
     */
    private static final int MAX_CHARS = 10000;

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file from the local filesystem.";
    }

    /**
     * 返回参数的 JSON Schema，告诉 LLM 这个工具需要一个 "path" 参数。
     * LLM 看到这个定义后就知道应该传什么参数。
     */
    @Override
    public JsonNode getParameters() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        // 定义 "path" 参数：字符串类型，必填
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ObjectNode pathProp = JsonNodeFactory.instance.objectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "Absolute or relative path to the file to read");
        properties.set("path", pathProp);

        params.set("properties", properties);

        JsonNodeFactory.instance.arrayNode().add("path");
        params.putArray("required").add("path");
        return params;
    }

    /**
     * 执行文件读取。
     *
     * <p>执行流程：解析 path 参数 → 检查文件存在性和类型 → 读取内容 → 截断超长文件</p>
     */
    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        String pathStr = arguments.get("path").asText();
        Path path = Paths.get(pathStr);

        // 前置检查：文件是否存在
        if (!Files.exists(path)) {
            throw new ToolExecutionException("File not found: " + pathStr);
        }

        // 前置检查：是否是普通文件（不是目录、符号链接等）
        if (!Files.isRegularFile(path)) {
            throw new ToolExecutionException("Not a regular file: " + pathStr);
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            // 超长文件截断，防止消耗过多 token
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS)
                        + "\n\n... [truncated at " + MAX_CHARS + " characters]";
            }
            return content;
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to read file: " + e.getMessage(), e);
        }
    }
}
