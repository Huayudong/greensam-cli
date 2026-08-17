package com.greensamcli.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
import com.greensamcli.agent.ToolExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * "内容搜索"工具——让 LLM 能够在本地文件中按正则搜索内容。
 *
 * <p>当用户说"找出项目里所有调用 {@code foo()} 的地方"时，LLM 会调用此工具，
 * 自动构造参数 {@code {"pattern": "foo\\(\\)", "path": "src"}}。
 * 没有它，agent 只能靠 list_files 盲找，无法定位"谁调用了我 / 定义在哪"。</p>
 *
 * <p><b>三种输出模式</b>（{@link OutputMode}，wire 名 content / files_with_matches / count）：</p>
 * <ul>
 *   <li>{@code content}（默认）：返回 {@code 路径:行号:命中行}，适合定位具体位置</li>
 *   <li>{@code files_with_matches}：只返回命中文件的路径，适合"哪些文件涉及"</li>
 *   <li>{@code count}：返回 {@code 路径:命中次数}，适合评估影响范围</li>
 * </ul>
 *
 * <p><b>护栏</b>：递归遍历时跳过构建产物 / 版本控制等垃圾目录（{@link #JUNK_DIRS}），
 * 跳过二进制文件（含 NUL 字节），单文件超过 {@link #MAX_FILE_BYTES} 跳过，
 * 命中结果按 {@code max_results}（默认 {@value #DEFAULT_MAX_RESULTS}）截断，
 * 单行输出截断到 {@value #MAX_LINE_CHARS} 字符——共同防止把巨量内容灌入上下文撑爆 token。</p>
 *
 * @author Macro Ray
 * @since 2026-08-13
 */
@Slf4j
public class GrepTool extends AbstractTool<GrepTool.Args> {

    /**
     * 遍历时跳过的目录名（构建产物、依赖、版本控制、IDE 配置等）。
     * 既避免噪音结果，又避免无谓扫描大目录。
     */
    private static final List<String> JUNK_DIRS = List.of(
            ".git", ".svn", ".hg", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist"
    );

    /** 单文件大小上限（字节）：超过则跳过，避免把大日志 / 产物整体读入。 */
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    /** 默认最大命中条数。 */
    private static final int DEFAULT_MAX_RESULTS = 100;
    /** 单行输出截断长度（字符）。 */
    private static final int MAX_LINE_CHARS = 500;
    /** 单次扫描文件数上限，防止遍历超大型目录树耗时过久。 */
    private static final int MAX_FILES_SCANNED = 2000;

    public GrepTool() {
        super(Args.class);
    }

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "Search file contents with a regular expression. "
                + "output_mode: content (path:line:text, default), files_with_matches, or count. "
                + "Use include to filter by filename glob (e.g. *.java).";
    }

    /**
     * 输出模式枚举。wire 名（对模型的取值）通过 {@code @JsonProperty} 声明，
     * 参数 Schema 会自动带出 enum 取值列表，非法取值在参数绑定期即报错。
     */
    public enum OutputMode {
        /** 逐行输出命中内容：路径:行号:文本 */
        @JsonProperty("content")
        CONTENT,
        /** 仅输出命中文件路径 */
        @JsonProperty("files_with_matches")
        FILES_WITH_MATCHES,
        /** 输出路径:命中次数 */
        @JsonProperty("count")
        COUNT
    }

    /**
     * 参数声明：pattern 必填；path / include / output_mode / case_insensitive / max_results 可选。
     * 参数 Schema 由 {@link AbstractTool} 依据此 record 自动生成。
     */
    public record Args(
            @Param(value = "Regular expression to search for", required = true) String pattern,
            @Param("File or directory to search. Defaults to current directory.") String path,
            @Param("Filename glob filter, e.g. *.java. Only matching files are searched.") String include,
            @Param("Output mode: content | files_with_matches | count. Default content.")
            @JsonProperty("output_mode") OutputMode outputMode,
            @Param("Case-insensitive match. Default false.")
            @JsonProperty("case_insensitive") Boolean caseInsensitive,
            @Param("Max results to return (lines for content, files otherwise). Default 100.")
            @JsonProperty("max_results") Integer maxResults) {

        public Args {
            if (path == null) {
                path = ".";
            }
            if (outputMode == null) {
                outputMode = OutputMode.CONTENT;
            }
            if (caseInsensitive == null) {
                caseInsensitive = false;
            }
            if (maxResults == null) {
                maxResults = DEFAULT_MAX_RESULTS;
            }
        }
    }

    /**
     * 执行内容搜索。
     *
     * @param args 已绑定的参数 record，可选参数已兜底默认值
     * @return 按 output_mode 格式化的搜索结果；无命中时返回明确的 no-matches 提示
     * @throws ToolExecutionException 正则非法、路径不存在、遍历失败时抛出
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        Pattern pattern = compilePattern(args.pattern(), args.caseInsensitive());
        PathMatcher includeMatcher = args.include() != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + args.include()) : null;

        Path root = Paths.get(args.path());
        if (!Files.exists(root)) {
            throw new ToolExecutionException("Path not found: " + args.path());
        }

        List<Path> targets = collectTargetFiles(root, includeMatcher);

        SearchResult result = search(targets, pattern, args.outputMode(), args.maxResults());
        return formatResult(result, args.outputMode());
    }

    /**
     * 编译正则，非法时包装成 ToolExecutionException。
     */
    private Pattern compilePattern(String patternStr, boolean caseInsensitive) throws ToolExecutionException {
        try {
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            return Pattern.compile(patternStr, flags);
        } catch (PatternSyntaxException e) {
            throw new ToolExecutionException("Invalid regex: " + e.getMessage(), e);
        }
    }

    /**
     * 收集待搜索文件列表：单文件直接返回；目录则递归遍历，跳过垃圾目录与超大文件，
     * 受 {@link #MAX_FILES_SCANNED} 约束。
     */
    private List<Path> collectTargetFiles(Path root, PathMatcher includeMatcher) throws ToolExecutionException {
        if (Files.isRegularFile(root)) {
            return List.of(root);
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 跳过垃圾目录：不进入子树
                    String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(root) && JUNK_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files.size() >= MAX_FILES_SCANNED) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Failed to access file during grep, skipping: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to walk path: " + e.getMessage(), e);
        }
        return files;
    }

    /**
     * 在目标文件列表上执行搜索，按 mode 收集结果，受 maxResults 约束。
     */
    private SearchResult search(List<Path> targets, Pattern pattern, OutputMode mode, int maxResults) {
        SearchResult result = new SearchResult(mode);
        for (Path file : targets) {
            if (result.isFull(maxResults)) {
                result.truncated = true;
                break;
            }
            searchOneFile(file, pattern, mode, maxResults, result);
        }
        return result;
    }

    /**
     * 搜索单个文件：二进制（含 NUL）直接跳过；逐行匹配并按 mode 记录。
     */
    private void searchOneFile(Path file, Pattern pattern, OutputMode mode, int maxResults, SearchResult result) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("Failed to read file during grep, skipping: {}", file);
            return;
        }
        // 二进制文件跳过（含 NUL 字节的启发式判定）
        if (isBinary(bytes)) {
            return;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);

        int fileMatches = 0;
        for (int i = 0; i < lines.length; i++) {
            if (result.isFull(maxResults)) {
                result.truncated = true;
                break;
            }
            if (pattern.matcher(lines[i]).find()) {
                fileMatches++;
                if (mode == OutputMode.CONTENT) {
                    result.contentLines.add(file + ":" + (i + 1) + ":" + truncateLine(lines[i]));
                }
            }
        }
        if (fileMatches > 0) {
            if (mode == OutputMode.FILES_WITH_MATCHES) {
                result.matchedFiles.add(file.toString());
            } else if (mode == OutputMode.COUNT) {
                result.countLines.add(file + ":" + fileMatches);
            }
        }
    }

    /**
     * 二进制判定：字节流含 NUL 视为二进制。
     */
    private static boolean isBinary(byte[] bytes) {
        // 仅扫描前 8KB 做启发式判定，足够区分代码 / 配置与二进制
        int limit = Math.min(bytes.length, 8 * 1024);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单行输出截断，避免超长行（如压缩 JS）撑爆上下文。
     */
    private static String truncateLine(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + " ... [truncated]";
    }

    /**
     * 把搜索结果按 mode 格式化为文本。无命中时返回明确提示。
     */
    private String formatResult(SearchResult result, OutputMode mode) {
        if (result.isEmpty()) {
            return "No matches found.";
        }
        StringBuilder sb = new StringBuilder();
        List<String> lines = mode == OutputMode.CONTENT ? result.contentLines
                : mode == OutputMode.FILES_WITH_MATCHES ? result.matchedFiles : result.countLines;
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        if (result.truncated) {
            sb.append("\n... [results truncated]\n");
        }
        return sb.toString().trim();
    }

    /**
     * 搜索过程中的可变结果容器，按 mode 区分收集的字段。
     */
    private static final class SearchResult {
        final OutputMode mode;
        final List<String> contentLines = new ArrayList<>();
        final List<String> matchedFiles = new ArrayList<>();
        final List<String> countLines = new ArrayList<>();
        boolean truncated = false;

        SearchResult(OutputMode mode) {
            this.mode = mode;
        }

        boolean isEmpty() {
            return contentLines.isEmpty() && matchedFiles.isEmpty() && countLines.isEmpty();
        }

        /**
         * 是否已达 maxResults 上限。content 模式按命中行计，files/count 模式按命中文件计。
         */
        boolean isFull(int maxResults) {
            int size = mode == OutputMode.CONTENT ? contentLines.size()
                    : mode == OutputMode.FILES_WITH_MATCHES ? matchedFiles.size() : countLines.size();
            return size >= maxResults;
        }
    }
}
