package com.greensamcli.tools;

import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
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
public class ReadFileTool extends AbstractTool<ReadFileTool.Args> {

    /**
     * 单次读取的最大字符数，超出部分截断
     */
    private static final int MAX_CHARS = 10000;

    public ReadFileTool() {
        super(Args.class);
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a file from the local filesystem.";
    }

    /**
     * 参数声明：path 必填。参数 Schema 由 {@link AbstractTool} 依据此 record 自动生成。
     */
    public record Args(
            @Param(value = "Absolute or relative path to the file to read", required = true) String path) {
    }

    /**
     * 执行文件读取。
     *
     * <p>执行流程：取 path 参数 → 检查文件存在性和类型 → 读取内容 → 截断超长文件</p>
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        String pathStr = args.path();
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
