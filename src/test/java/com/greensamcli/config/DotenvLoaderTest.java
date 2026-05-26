package com.greensamcli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DotenvLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsBasicKeyValue() throws Exception {
        Files.writeString(tempDir.resolve(".env"), "OPENAI_API_KEY=sk-test-123\n");
        Map<String, String> env = DotenvLoader.load(tempDir);

        assertEquals("sk-test-123", env.get("OPENAI_API_KEY"));
    }

    @Test
    void handlesMultipleEntries() throws Exception {
        Files.writeString(tempDir.resolve(".env"),
                "OPENAI_API_KEY=sk-test\n"
                + "GREENSAM_MODEL=gpt-4o\n"
                + "OPENAI_BASE_URL=https://api.openai.com/v1\n");
        Map<String, String> env = DotenvLoader.load(tempDir);

        assertEquals(3, env.size());
        assertEquals("sk-test", env.get("OPENAI_API_KEY"));
        assertEquals("gpt-4o", env.get("GREENSAM_MODEL"));
    }

    @Test
    void skipsCommentsAndBlankLines() throws Exception {
        Files.writeString(tempDir.resolve(".env"),
                "# This is a comment\n"
                + "\n"
                + "OPENAI_API_KEY=sk-test\n"
                + "  # Another comment\n"
                + "GREENSAM_MODEL=gpt-4o\n");
        Map<String, String> env = DotenvLoader.load(tempDir);

        assertEquals(2, env.size());
        assertEquals("sk-test", env.get("OPENAI_API_KEY"));
    }

    @Test
    void stripsQuotes() throws Exception {
        Files.writeString(tempDir.resolve(".env"),
                "KEY1=\"double quoted\"\n"
                + "KEY2='single quoted'\n"
                + "KEY3=unquoted\n");
        Map<String, String> env = DotenvLoader.load(tempDir);

        assertEquals("double quoted", env.get("KEY1"));
        assertEquals("single quoted", env.get("KEY2"));
        assertEquals("unquoted", env.get("KEY3"));
    }

    @Test
    void returnsEmptyMapWhenFileMissing() {
        Map<String, String> env = DotenvLoader.load(tempDir);

        assertTrue(env.isEmpty());
    }

    @Test
    void skipsMalformedLines() throws Exception {
        Files.writeString(tempDir.resolve(".env"),
                "NO_EQUALS_SIGN\n"
                + "VALID_KEY=value\n");
        Map<String, String> env = DotenvLoader.load(tempDir);

        assertEquals(1, env.size());
        assertEquals("value", env.get("VALID_KEY"));
    }
}
