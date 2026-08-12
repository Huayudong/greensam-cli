package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greensamcli.agent.ToolExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
