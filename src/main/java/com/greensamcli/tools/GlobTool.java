package com.greensamcli.tools;

import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
import com.greensamcli.agent.ToolExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "文件名搜索"工具——让 LLM 能够按 glob 模式查找文件路径。
 *
 * <p>当用户说"找出所有的 Java 测试类"时，LLM 会调用此工具，构造参数
 * {@code {"pattern": "*.java", "path": "src"}}。</p>
 *
 * <p><b>与 {@link GrepTool} 的区别</b>：grep 按内容搜索（找代码里写了什么），
 * glob 按文件名 / 路径模式搜索（找哪些文件存在）。两者互补，让 agent 能自主定位文件。</p>
 *
 * <p><b>glob 语法</b>（JDK {@link PathMatcher} 的 glob 语法）：</p>
 * <ul>
 *   <li>{@code *} —— 单层任意字符</li>
 *   <li>{@code **} —— 跨任意层级目录（与斜杠配合表示递归）</li>
 *   <li>{@code ?} —— 单个字符</li>
 *   <li>{@code {a,b}} —— 枚举</li>
 *   <li>递归匹配全部 Java 文件：pattern 写成「双星号、斜杠、星号点 java」；仅匹配一层用「星号点 文件名」。</li>
 * </ul>
 *
 * <p><b>护栏</b>：跳过 {@link #JUNK_DIRS} 中的目录，结果上限 {@value #MAX_RESULTS} 条，
 * 按路径排序输出（相对搜索根），避免噪音与上下文膨胀。</p>
 *
 * @author Macro Ray
 * @since 2026-08-13
 */
@Slf4j
public class GlobTool extends AbstractTool<GlobTool.Args> {

    /** 遍历时跳过的目录名，与 {@link GrepTool} 保持一致。 */
    private static final List<String> JUNK_DIRS = List.of(
            ".git", ".svn", ".hg", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist"
    );
    /** 单次返回的匹配上限，防止超大目录树返回过多结果。 */
    private static final int MAX_RESULTS = 1000;

    public GlobTool() {
        super(Args.class);
    }

    @Override
    public String getName() {
        return "glob";
    }

    @Override
    public String getDescription() {
        return "Find files by glob pattern (e.g. **/*.java, src/**/*.md). "
                + "Use ** to match across directories. Returns matching file paths.";
    }

    /**
     * 参数声明：pattern 必填，path 可选（默认当前目录）。
     * 参数 Schema 由 {@link AbstractTool} 依据此 record 自动生成。
     */
    public record Args(
            @Param(value = "Glob pattern, e.g. **/*.java or *.md", required = true) String pattern,
            @Param("Directory to search in. Defaults to current directory.") String path) {

        public Args {
            if (path == null) {
                path = ".";
            }
        }
    }

    /**
     * 执行文件名模式匹配。
     *
     * @param args 已绑定的参数 record，path 已兜底为当前目录
     * @return 匹配的文件路径列表（相对搜索根，每行一个），末尾带计数；无匹配时返回明确提示
     * @throws ToolExecutionException 路径不存在、glob 非法、遍历失败时抛出
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        String patternStr = args.pattern();
        String pathStr = args.path();

        Path root = Paths.get(pathStr);
        if (!Files.exists(root)) {
            throw new ToolExecutionException("Path not found: " + pathStr);
        }
        if (!Files.isDirectory(root)) {
            throw new ToolExecutionException("Not a directory: " + pathStr);
        }

        PathMatcher matcher = compileMatcher(patternStr);

        List<Path> matched = walkAndMatch(root, matcher);
        if (matched.isEmpty()) {
            return "No files matched pattern: " + patternStr;
        }

        // 相对根路径，便于阅读；按路径排序
        matched.sort(Comparator.comparing(p -> root.relativize(p).toString()));

        StringBuilder sb = new StringBuilder();
        for (Path p : matched) {
            sb.append(root.relativize(p)).append("\n");
        }
        sb.append("\n(").append(matched.size()).append(" matches)");
        return sb.toString().trim();
    }

    /**
     * 编译 glob 匹配器。
     */
    private PathMatcher compileMatcher(String patternStr) throws ToolExecutionException {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + patternStr);
        } catch (IllegalArgumentException e) {
            throw new ToolExecutionException("Invalid glob pattern: " + e.getMessage(), e);
        }
    }

    /**
     * 递归遍历根目录，匹配相对路径，跳过垃圾目录，受 {@link #MAX_RESULTS} 约束。
     * 用相对路径参与匹配（而非仅文件名），使双星号跨层模式能匹配嵌套文件。
     */
    private List<Path> walkAndMatch(Path root, PathMatcher matcher) throws ToolExecutionException {
        List<Path> matched = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(root) && JUNK_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matched.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    // 用相对路径参与匹配，让 ** 能跨目录生效
                    if (matcher.matches(root.relativize(file))) {
                        matched.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to access file during glob, skipping: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to walk path: " + e.getMessage(), e);
        }
        return matched;
    }
}
