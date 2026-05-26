package com.greensamcli.config;

import lombok.Getter;

import java.nio.file.Path;
import java.util.Map;

/**
 * 应用配置——从环境变量和 .env 文件加载运行时参数。
 *
 * <p>优先级：System.getenv() > .env 文件 > 默认值</p>
 *
 * <p>支持的配置项：</p>
 * <table>
 *   <tr><th>变量名</th><th>必须</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>OPENAI_API_KEY</td><td>是</td><td>-</td><td>API 密钥</td></tr>
 *   <tr><td>OPENAI_BASE_URL</td><td>否</td><td>https://api.openai.com/v1</td><td>API 地址</td></tr>
 *   <tr><td>GREENSAM_MODEL</td><td>否</td><td>gpt-4o</td><td>模型名称</td></tr>
 *   <tr><td>GREENSAM_SYSTEM_PROMPT</td><td>否</td><td>内置提示词</td><td>系统提示词</td></tr>
 *   <tr><td>GREENSAM_STREAMING</td><td>否</td><td>true</td><td>是否启用流式输出</td></tr>
 * </table>
 */
public class AppConfig {

    private final Map<String, String> fallbackEnv;
    @Getter
    private final String apiKey;
    @Getter
    private final String baseUrl;
    @Getter
    private final String model;
    @Getter
    private final String systemPrompt;
    @Getter
    private final boolean streaming;

    public AppConfig() {
        this(Path.of("").toAbsolutePath());
    }

    /** Package-private for testing with a custom directory. */
    AppConfig(Path projectRoot) {
        this.fallbackEnv = DotenvLoader.load(projectRoot);
        this.apiKey = requireEnv("OPENAI_API_KEY");
        this.baseUrl = getEnv("OPENAI_BASE_URL", "https://api.openai.com/v1");
        this.model = getEnv("GREENSAM_MODEL", "gpt-4o");
        this.systemPrompt = getEnv("GREENSAM_SYSTEM_PROMPT",
                "You are a helpful CLI assistant with access to file tools. Be concise and helpful.");
        this.streaming = !"false".equalsIgnoreCase(resolveEnv("GREENSAM_STREAMING"));
    }

    /** System.getenv() 优先，.env 文件作为回退。 */
    private String resolveEnv(String name) {
        String value = System.getenv(name);
        if (value != null) {
            return value;
        }
        return fallbackEnv.get(name);
    }

    /** 读取必须的配置，缺失时抛出异常（fail-fast）。 */
    private String requireEnv(String name) {
        String value = resolveEnv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Required environment variable not set: " + name
                    + ". Set it in system env or create a .env file in the project root.");
        }
        return value.trim();
    }

    /** 读取可选的配置，缺失时使用默认值。 */
    private String getEnv(String name, String defaultValue) {
        String value = resolveEnv(name);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
