package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.ToolExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readFileTool_readsExistingFile(@TempDir Path tempDir) throws Exception {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello, World!");

        ReadFileTool tool = new ReadFileTool();
        JsonNode args = mapper.readTree("{\"path\":\"" + testFile.toString().replace("\\", "\\\\") + "\"}");

        String result = tool.execute(args);
        assertEquals("Hello, World!", result);
    }

    @Test
    void readFileTool_fileNotFound() throws Exception {
        ReadFileTool tool = new ReadFileTool();
        JsonNode args = mapper.readTree("{\"path\":\"/nonexistent/file.txt\"}");

        assertThrows(ToolExecutionException.class, () -> tool.execute(args));
    }

    @Test
    void readFileTool_truncatesLargeFile(@TempDir Path tempDir) throws Exception {
        Path largeFile = tempDir.resolve("large.txt");
        String content = "x".repeat(15000);
        Files.writeString(largeFile, content);

        ReadFileTool tool = new ReadFileTool();
        JsonNode args = mapper.readTree("{\"path\":\"" + largeFile.toString().replace("\\", "\\\\") + "\"}");

        String result = tool.execute(args);
        assertTrue(result.contains("truncated"));
        assertTrue(result.length() < 11000);
    }

    @Test
    void listFilesTool_listsDirectoryContents(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("file1.txt"));
        Files.createFile(tempDir.resolve("file2.java"));
        Files.createDirectory(tempDir.resolve("subdir"));

        ListFilesTool tool = new ListFilesTool();
        JsonNode args = mapper.readTree("{\"path\":\"" + tempDir.toString().replace("\\", "\\\\") + "\"}");

        String result = tool.execute(args);

        assertTrue(result.contains("f  file1.txt"));
        assertTrue(result.contains("f  file2.java"));
        assertTrue(result.contains("d  subdir"));
    }

    @Test
    void listFilesTool_notADirectory(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "content");

        ListFilesTool tool = new ListFilesTool();
        JsonNode args = mapper.readTree("{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}");

        assertThrows(ToolExecutionException.class, () -> tool.execute(args));
    }

    // ==================== WriteFileTool ====================

    /**
     * 用 ObjectMapper 构造含 path + content 的参数，避免大内容 / 特殊字符的 JSON 转义问题。
     */
    private JsonNode writeArgs(Path path, String content) {
        return mapper.createObjectNode()
                .put("path", path.toString())
                .put("content", content);
    }

    @Test
    void writeFileTool_createsNewFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("out.txt");

        WriteFileTool tool = new WriteFileTool();
        String result = tool.execute(writeArgs(target, "Hello, World!"));

        assertTrue(result.contains("Created"));
        assertEquals("Hello, World!", Files.readString(target));
    }

    @Test
    void writeFileTool_overwritesExistingFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("out.txt");
        Files.writeString(target, "old content");

        WriteFileTool tool = new WriteFileTool();
        String result = tool.execute(writeArgs(target, "new content"));

        assertTrue(result.contains("Overwrote"));
        assertTrue(result.contains("was"));
        assertEquals("new content", Files.readString(target));
    }

    @Test
    void writeFileTool_createsParentDirectories(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("a/b/c/deep.txt");

        WriteFileTool tool = new WriteFileTool();
        tool.execute(writeArgs(target, "nested"));

        assertEquals("nested", Files.readString(target));
    }

    @Test
    void writeFileTool_rejectsOversizedContent(@TempDir Path tempDir) {
        Path target = tempDir.resolve("big.txt");
        // 工具上限为 5 * 1024 * 1024 字节，这里构造超出 1 字节的内容
        String tooLarge = "x".repeat(5 * 1024 * 1024 + 1);

        WriteFileTool tool = new WriteFileTool();
        assertThrows(ToolExecutionException.class, () -> tool.execute(writeArgs(target, tooLarge)));
    }

    @Test
    void writeFileTool_rejectsDirectoryPath(@TempDir Path tempDir) {
        WriteFileTool tool = new WriteFileTool();
        // tempDir 本身是目录，禁止把目录当文件覆盖
        assertThrows(ToolExecutionException.class, () -> tool.execute(writeArgs(tempDir, "x")));
    }

    @Test
    void writeFileTool_emptyContentCreatesEmptyFile(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("empty.txt");

        WriteFileTool tool = new WriteFileTool();
        tool.execute(writeArgs(target, ""));

        assertEquals(0L, Files.size(target));
    }

    // ==================== EditFileTool ====================

    /**
     * 构造 edit_file 参数，避免正则特殊字符的 JSON 转义问题。
     */
    private JsonNode editArgs(Path path, String oldStr, String newStr, boolean replaceAll) {
        return mapper.createObjectNode()
                .put("path", path.toString())
                .put("old_string", oldStr)
                .put("new_string", newStr)
                .put("replace_all", replaceAll);
    }

    @Test
    void editFileTool_replacesUniqueMatch(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("code.txt");
        Files.writeString(target, "alpha beta gamma");

        EditFileTool tool = new EditFileTool();
        String result = tool.execute(editArgs(target, "beta", "BETA", false));

        assertTrue(result.contains("replaced 1"));
        assertEquals("alpha BETA gamma", Files.readString(target));
    }

    @Test
    void editFileTool_replacesAllOccurrences(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("code.txt");
        Files.writeString(target, "a a a");

        EditFileTool tool = new EditFileTool();
        String result = tool.execute(editArgs(target, "a", "b", true));

        assertTrue(result.contains("replaced 3"));
        assertEquals("b b b", Files.readString(target));
    }

    @Test
    void editFileTool_notFoundThrows(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("code.txt");
        Files.writeString(target, "hello world");

        EditFileTool tool = new EditFileTool();
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(editArgs(target, "missing", "x", false)));
    }

    @Test
    void editFileTool_notUniqueThrowsWithoutReplaceAll(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("code.txt");
        Files.writeString(target, "x y x");

        EditFileTool tool = new EditFileTool();
        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> tool.execute(editArgs(target, "x", "z", false)));
        assertTrue(ex.getMessage().contains("not unique"));
    }

    @Test
    void editFileTool_rejectsIdenticalOldNew(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("code.txt");
        Files.writeString(target, "same");

        EditFileTool tool = new EditFileTool();
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(editArgs(target, "same", "same", false)));
    }

    @Test
    void editFileTool_rejectsDirectory(@TempDir Path tempDir) {
        EditFileTool tool = new EditFileTool();
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(editArgs(tempDir, "x", "y", false)));
    }

    @Test
    void editFileTool_rejectsEmptyOldString(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("code.txt");
        Files.writeString(target, "data");

        EditFileTool tool = new EditFileTool();
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(editArgs(target, "", "y", false)));
    }

    // ==================== GrepTool ====================

    /**
     * 构造 grep 参数，null 字段不写入，覆盖默认值语义。
     */
    private JsonNode grepArgs(String pattern, String path, String include, String mode,
                              boolean ci, Integer maxResults) {
        ObjectNode node = mapper.createObjectNode().put("pattern", pattern);
        if (path != null) node.put("path", path);
        if (include != null) node.put("include", include);
        if (mode != null) node.put("output_mode", mode);
        if (ci) node.put("case_insensitive", true);
        if (maxResults != null) node.put("max_results", maxResults);
        return node;
    }

    @Test
    void grepTool_findsContentMatches(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world\nhello again\nbye");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("hello", file.toString(), null, "content", false, null));

        assertTrue(result.contains(file + ":1:hello world"));
        assertTrue(result.contains(file + ":2:hello again"));
        assertFalse(result.contains("bye"));
    }

    @Test
    void grepTool_caseInsensitiveCount(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "Hello\nHELLO\n");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("hello", file.toString(), null, "count", true, null));

        // count 模式：单文件 2 次命中
        assertTrue(result.contains(file + ":2"));
    }

    @Test
    void grepTool_regexPattern(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "foo123bar");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("[0-9]+", file.toString(), null, "content", false, null));

        assertTrue(result.contains("foo123bar"));
    }

    @Test
    void grepTool_filesWithMatchesMode(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("match.txt"), "target");
        Files.writeString(tempDir.resolve("nomatch.txt"), "nothing");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("target", tempDir.toString(), null,
                "files_with_matches", false, null));

        assertTrue(result.contains("match.txt"));
        assertFalse(result.contains("nomatch.txt"));
    }

    @Test
    void grepTool_includeFilter(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "needle");
        Files.writeString(tempDir.resolve("B.txt"), "needle");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("needle", tempDir.toString(), "*.java",
                "content", false, null));

        assertTrue(result.contains("A.java"));
        assertFalse(result.contains("B.txt"));
    }

    @Test
    void grepTool_skipsJunkDir(@TempDir Path tempDir) throws Exception {
        Path junk = tempDir.resolve("target");
        Files.createDirectories(junk);
        Files.writeString(junk.resolve("in_target.txt"), "needle");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("needle", tempDir.toString(), null,
                "files_with_matches", false, null));

        assertEquals("No matches found.", result);
    }

    @Test
    void grepTool_maxResultsTruncates(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hit\nhit\nhit\nhit\nhit\n");

        GrepTool tool = new GrepTool();
        String result = tool.execute(grepArgs("hit", file.toString(), null, "content", false, 2));

        assertTrue(result.contains("truncated"));
    }

    // ==================== GlobTool ====================

    /**
     * 构造 glob 参数，null path 不写入（走默认当前目录）。
     */
    private JsonNode globArgs(String pattern, String path) {
        ObjectNode node = mapper.createObjectNode().put("pattern", pattern);
        if (path != null) node.put("path", path);
        return node;
    }

    @Test
    void globTool_matchesRecursivePattern(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("a/b"));
        Files.createFile(tempDir.resolve("a/b/deep.txt"));

        GlobTool tool = new GlobTool();
        String result = tool.execute(globArgs("**/*.txt", tempDir.toString()));

        assertTrue(result.contains("deep.txt"));
        assertTrue(result.contains("matches"));
    }

    @Test
    void globTool_singleLevelPattern(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("sub"));
        Files.createFile(tempDir.resolve("root.txt"));
        Files.createFile(tempDir.resolve("sub/nested.txt"));

        GlobTool tool = new GlobTool();
        String result = tool.execute(globArgs("*.txt", tempDir.toString()));

        assertTrue(result.contains("root.txt"));
        assertFalse(result.contains("nested.txt"));
    }

    @Test
    void globTool_noMatch(@TempDir Path tempDir) {
        GlobTool tool = new GlobTool();
        String result = tool.execute(globArgs("*.nonexistent", tempDir.toString()));

        assertTrue(result.contains("No files matched"));
    }

    @Test
    void globTool_skipsJunkDir(@TempDir Path tempDir) throws Exception {
        Path junk = tempDir.resolve("target");
        Files.createDirectories(junk);
        Files.createFile(junk.resolve("build.txt"));

        GlobTool tool = new GlobTool();
        String result = tool.execute(globArgs("**/*.txt", tempDir.toString()));

        assertFalse(result.contains("build.txt"));
        assertTrue(result.contains("No files matched"));
    }

    // ==================== ExecuteCommandTool ====================
    // 说明：以下用例会真实启动子进程，仅用跨平台命令（echo / exit），避免平台依赖。

    /**
     * 构造 execute_command 参数。
     */
    private JsonNode execArgs(String command, String cwd, Integer timeout) {
        ObjectNode node = mapper.createObjectNode().put("command", command);
        if (cwd != null) node.put("cwd", cwd);
        if (timeout != null) node.put("timeout_seconds", timeout);
        return node;
    }

    @Test
    void executeCommandTool_runsEchoAndCapturesOutput() throws Exception {
        ExecuteCommandTool tool = new ExecuteCommandTool();
        String result = tool.execute(execArgs("echo hello_cli", null, null));

        assertTrue(result.contains("exit_code=0"));
        assertTrue(result.contains("hello_cli"));
    }

    @Test
    void executeCommandTool_reflectsNonZeroExitCode() throws Exception {
        ExecuteCommandTool tool = new ExecuteCommandTool();
        String result = tool.execute(execArgs("exit 7", null, null));

        assertTrue(result.contains("exit_code=7"));
    }

    @Test
    void executeCommandTool_blocksDangerousCommand() {
        ExecuteCommandTool tool = new ExecuteCommandTool();
        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> tool.execute(execArgs("rm -rf /", null, null)));
        assertTrue(ex.getMessage().contains("denylist"));
    }

    @Test
    void executeCommandTool_rejectsEmptyCommand() {
        ExecuteCommandTool tool = new ExecuteCommandTool();
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(execArgs("", null, null)));
    }

    @Test
    void executeCommandTool_rejectsInvalidCwd() {
        ExecuteCommandTool tool = new ExecuteCommandTool();
        assertThrows(ToolExecutionException.class,
                () -> tool.execute(execArgs("echo hi", "/no/such/dir/xyz", null)));
    }

    // ==================== 参数 Schema 形状 ====================
    // 锁定各工具对模型的 wire 契约（参数名 / required 顺序 / 枚举取值），
    // 防止 record 迁移后的命名漂移（如 replace_all 误映射为 replaceAll）。

    /**
     * 按声明顺序提取 Schema 的 properties 参数名列表。
     */
    private List<String> propertyNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * 按顺序提取 Schema 的 required 参数名列表。
     */
    private List<String> requiredNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.get("required").forEach(node -> names.add(node.asText()));
        return names;
    }

    @Test
    void schema_readFileToolShape() {
        JsonNode schema = new ReadFileTool().getParameters();

        assertEquals(List.of("path"), propertyNames(schema));
        assertEquals(List.of("path"), requiredNames(schema));
    }

    @Test
    void schema_listFilesToolShape() {
        JsonNode schema = new ListFilesTool().getParameters();

        assertEquals(List.of("path"), propertyNames(schema));
        assertEquals(List.of("path"), requiredNames(schema));
    }

    @Test
    void schema_writeFileToolShape() {
        JsonNode schema = new WriteFileTool().getParameters();

        assertEquals(List.of("path", "content"), propertyNames(schema));
        assertEquals(List.of("path", "content"), requiredNames(schema));
    }

    @Test
    void schema_editFileToolShape() {
        JsonNode schema = new EditFileTool().getParameters();

        assertEquals(List.of("path", "old_string", "new_string", "replace_all"), propertyNames(schema));
        assertEquals(List.of("path", "old_string", "new_string"), requiredNames(schema));
        assertEquals("boolean", schema.get("properties").get("replace_all").get("type").asText());
    }

    @Test
    void schema_globToolShape() {
        JsonNode schema = new GlobTool().getParameters();

        assertEquals(List.of("pattern", "path"), propertyNames(schema));
        assertEquals(List.of("pattern"), requiredNames(schema));
    }

    @Test
    void schema_grepToolShape() {
        JsonNode schema = new GrepTool().getParameters();

        assertEquals(List.of("pattern", "path", "include", "output_mode", "case_insensitive", "max_results"),
                propertyNames(schema));
        assertEquals(List.of("pattern"), requiredNames(schema));
        assertEquals("string", schema.get("properties").get("output_mode").get("type").asText());
        List<String> enumValues = new ArrayList<>();
        schema.get("properties").get("output_mode").get("enum").forEach(node -> enumValues.add(node.asText()));
        assertEquals(List.of("content", "files_with_matches", "count"), enumValues);
    }

    @Test
    void schema_executeCommandToolShape() {
        JsonNode schema = new ExecuteCommandTool().getParameters();

        assertEquals(List.of("command", "cwd", "timeout_seconds"), propertyNames(schema));
        assertEquals(List.of("command"), requiredNames(schema));
    }
}
