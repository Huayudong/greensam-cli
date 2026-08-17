package com.greensamcli.tools;

import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
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
public class ListFilesTool extends AbstractTool<ListFilesTool.Args> {

    public ListFilesTool() {
        super(Args.class);
    }

    @Override
    public String getName() {
        return "list_files";
    }

    @Override
    public String getDescription() {
        return "列出指定目录下的文件和子目录，每行以 d（目录）或 f（文件）开头标识类型。";
    }

    /**
     * 参数声明：path 必填。参数 Schema 由 {@link AbstractTool} 依据此 record 自动生成。
     */
    public record Args(
            @Param(value = "要列出的目录路径（绝对或相对路径）", required = true) String path) {
    }

    /**
     * 执行目录列表。
     * 使用 try-with-resources 确保 Files.list() 返回的 Stream 正确关闭
     * （Stream 持有底层文件系统资源，不关闭会导致资源泄漏）。
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        String pathStr = args.path();
        Path path = Paths.get(pathStr);

        if (!Files.exists(path)) {
            throw new ToolExecutionException("目录不存在: " + pathStr);
        }

        if (!Files.isDirectory(path)) {
            throw new ToolExecutionException("不是目录: " + pathStr);
        }

        StringBuilder sb = new StringBuilder();
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted().forEach(p -> {
                // "d" 表示目录，"f" 表示普通文件
                String type = Files.isDirectory(p) ? "d" : "f";
                sb.append(type).append("  ").append(p.getFileName()).append("\n");
            });
        } catch (IOException e) {
            throw new ToolExecutionException("列出目录失败: " + e.getMessage(), e);
        }

        return sb.toString().trim();
    }
}
