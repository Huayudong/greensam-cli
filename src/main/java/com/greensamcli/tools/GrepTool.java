package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Tool;
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
 * <p><b>三种输出模式</b>：</p>
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
public class GrepTool implements Tool {

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

    private static final String MODE_CONTENT = "content";
    private static final String MODE_FILES = "files_with_matches";
    private static final String MODE_COUNT = "count";

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
     * 参数 Schema：pattern 必填；path / include / output_mode / case_insensitive / max_results 可选。
     */
    @Override
    public JsonNode getParameters() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode properties = JsonNodeFactory.instance.objectNode();

        ObjectNode patternProp = JsonNodeFactory.instance.objectNode();
        patternProp.put("type", "string");
        patternProp.put("description", "Regular expression to search for");
        properties.set("pattern", patternProp);

        ObjectNode pathProp = JsonNodeFactory.instance.objectNode();
        pathProp.put("type", "string");
        pathProp.put("description", "File or directory to search. Defaults to current directory.");
        properties.set("path", pathProp);

        ObjectNode includeProp = JsonNodeFactory.instance.objectNode();
        includeProp.put("type", "string");
        includeProp.put("description", "Filename glob filter, e.g. *.java. Only matching files are searched.");
        properties.set("include", includeProp);

        ObjectNode modeProp = JsonNodeFactory.instance.objectNode();
        modeProp.put("type", "string");
        modeProp.put("description", "Output mode: content | files_with_matches | count. Default content.");
        properties.set("output_mode", modeProp);

        ObjectNode ciProp = JsonNodeFactory.instance.objectNode();
        ciProp.put("type", "boolean");
        ciProp.put("description", "Case-insensitive match. Default false.");
        properties.set("case_insensitive", ciProp);

        ObjectNode maxProp = JsonNodeFactory.instance.objectNode();
        maxProp.put("type", "integer");
        maxProp.put("description", "Max results to return (lines for content, files otherwise). Default 100.");
        properties.set("max_results", maxProp);

        params.set("properties", properties);
        params.putArray("required").add("pattern");
        return params;
    }

    /**
     * 执行内容搜索。
     *
     * @param arguments 含 {@code pattern}，可选 path / include / output_mode / case_insensitive / max_results
     * @return 按 output_mode 格式化的搜索结果；无命中时返回明确的 no-matches 提示
     * @throws ToolExecutionException 正则非法、路径不存在、遍历失败时抛出
     */
    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        String patternStr = arguments.get("pattern").asText();
        String pathStr = arguments.has("path") ? arguments.get("path").asText() : ".";
        String include = arguments.has("include") && !arguments.get("include").isNull()
                ? arguments.get("include").asText() : null;
        String mode = arguments.has("output_mode") ? arguments.get("output_mode").asText() : MODE_CONTENT;
        boolean caseInsensitive = arguments.has("case_insensitive") && arguments.get("case_insensitive").asBoolean();
        int maxResults = arguments.has("max_results") ? arguments.get("max_results").asInt() : DEFAULT_MAX_RESULTS;

        validateMode(mode);

        Pattern pattern = compilePattern(patternStr, caseInsensitive);
        PathMatcher includeMatcher = include != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + include) : null;

        Path root = Paths.get(pathStr);
        if (!Files.exists(root)) {
            throw new ToolExecutionException("Path not found: " + pathStr);
        }

        List<Path> targets = collectTargetFiles(root, includeMatcher);

        SearchResult result = search(targets, pattern, mode, maxResults);
        return formatResult(result, mode);
    }

    /**
     * 校验 output_mode 取值合法。
     */
    private void validateMode(String mode) throws ToolExecutionException {
        if (!MODE_CONTENT.equals(mode) && !MODE_FILES.equals(mode) && !MODE_COUNT.equals(mode)) {
            throw new ToolExecutionException(
                    "Invalid output_mode: " + mode + ". Use content | files_with_matches | count.");
        }
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
    private SearchResult search(List<Path> targets, Pattern pattern, String mode, int maxResults) {
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
    private void searchOneFile(Path file, Pattern pattern, String mode, int maxResults, SearchResult result) {
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
                if (MODE_CONTENT.equals(mode)) {
                    result.contentLines.add(file + ":" + (i + 1) + ":" + truncateLine(lines[i]));
                }
            }
        }
        if (fileMatches > 0) {
            if (MODE_FILES.equals(mode)) {
                result.matchedFiles.add(file.toString());
            } else if (MODE_COUNT.equals(mode)) {
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
    private String formatResult(SearchResult result, String mode) {
        if (result.isEmpty()) {
            return "No matches found.";
        }
        StringBuilder sb = new StringBuilder();
        List<String> lines = MODE_CONTENT.equals(mode) ? result.contentLines
                : MODE_FILES.equals(mode) ? result.matchedFiles : result.countLines;
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
        final String mode;
        final List<String> contentLines = new ArrayList<>();
        final List<String> matchedFiles = new ArrayList<>();
        final List<String> countLines = new ArrayList<>();
        boolean truncated = false;

        SearchResult(String mode) {
            this.mode = mode;
        }

        boolean isEmpty() {
            return contentLines.isEmpty() && matchedFiles.isEmpty() && countLines.isEmpty();
        }

        /**
         * 是否已达 maxResults 上限。content 模式按命中行计，files/count 模式按命中文件计。
         */
        boolean isFull(int maxResults) {
            int size = MODE_CONTENT.equals(mode) ? contentLines.size()
                    : MODE_FILES.equals(mode) ? matchedFiles.size() : countLines.size();
            return size >= maxResults;
        }
    }
}
