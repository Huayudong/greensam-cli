package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Tool;
import com.greensamcli.agent.ToolExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * "列出目录"工具——让 LLM 能够查看本地目录下的文件和子目录。
 *
 * <p>当用户说"看看 /tmp 下面有什么文件"时，LLM 会调用此工具。
 * 返回的结果中，每行以 "d"（目录）或 "f"（文件）开头标识类型，
 * 类似 Unix 的 {@code ls -l} 输出。</p>
 *
 * <p>典型输出：</p>
 * <pre>
 * d  subdir
 * f  file1.txt
 * f  file2.java
 * </pre>
 */
public class ListFilesTool implements Tool {

    @Override
    public String getName() {
        return "list_files";
    }

    @Override
    public String getDescription() {
        return "List files and directories in a given path.";
    }

    @Override
    public JsonNode getParameters() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ObjectNode pathProp = JsonNodeFactory.instance.objectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "Absolute or relative path to the directory to list");
        properties.set("path", pathProp);

        params.set("properties", properties);
        params.putArray("required").add("path");
        return params;
    }

    /**
     * 执行目录列表。
     * 使用 try-with-resources 确保 Files.list() 返回的 Stream 正确关闭
     * （Stream 持有底层文件系统资源，不关闭会导致资源泄漏）。
     */
    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        String pathStr = arguments.get("path").asText();
        Path path = Paths.get(pathStr);

        if (!Files.exists(path)) {
            throw new ToolExecutionException("Directory not found: " + pathStr);
        }

        if (!Files.isDirectory(path)) {
            throw new ToolExecutionException("Not a directory: " + pathStr);
        }

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted().forEach(p -> {
                // "d" 表示目录，"f" 表示普通文件
                String type = Files.isDirectory(p) ? "d" : "f";
                sb.append(type).append("  ").append(p.getFileName()).append("\n");
            });
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to list directory: " + e.getMessage(), e);
        }

        return sb.toString().trim();
    }
}
