package com.greensamcli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppConfig} 配置解析测试。
 *
 * <p>通过包级私有构造函数注入临时项目目录，用 .env 文件模拟环境变量回退，
 * 不依赖真实系统环境变量（若本机设置了同名环境变量，
 * System.getenv 优先会导致默认值用例失效——CI 与开发机均未设置该变量）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
class AppConfigTest {

    @TempDir
    Path tempDir;

    /**
     * 写入 .env 后构建 AppConfig。
     *
     * @param envContent .env 文件内容；传 null 则不创建文件
     */
    private AppConfig configWithEnvFile(String envContent) throws IOException {
        if (envContent != null) {
            Files.writeString(tempDir.resolve(".env"), envContent);
        }
        return new AppConfig(tempDir);
    }

    @Test
    void timeoutSeconds_defaultsTo300() throws IOException {
        AppConfig config = configWithEnvFile("OPENAI_API_KEY=test-key\n");
        assertEquals(300, config.getTimeoutSeconds());
    }

    @Test
    void timeoutSeconds_readsFromEnvFile() throws IOException {
        AppConfig config = configWithEnvFile(
                "OPENAI_API_KEY=test-key\nGREENSAM_TIMEOUT_SECONDS=7\n");
        assertEquals(7, config.getTimeoutSeconds());
    }

    @Test
    void timeoutSeconds_nonInteger_failsFastWithConfigName() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                configWithEnvFile("OPENAI_API_KEY=test-key\nGREENSAM_TIMEOUT_SECONDS=abc\n"));
        assertTrue(e.getMessage().contains("GREENSAM_TIMEOUT_SECONDS"),
                "报错信息应指明出错的配置项，实际: " + e.getMessage());
    }
}
