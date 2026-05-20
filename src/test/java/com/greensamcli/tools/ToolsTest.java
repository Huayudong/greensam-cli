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
}
