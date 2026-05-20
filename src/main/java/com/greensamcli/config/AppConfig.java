package com.greensamcli.config;

/**
 * 应用配置——从环境变量加载运行时参数。
 *
 * <p>使用环境变量而非配置文件，原因是：</p>
 * <ul>
 *   <li>API Key 等敏感信息不应出现在代码仓库中</li>
 *   <li>与 Docker/K8s 等部署环境的密钥管理兼容</li>
 *   <li>与 OpenAI 官方 SDK 的约定一致（OPENAI_API_KEY 是行业标准）</li>
 * </ul>
 *
 * <p>支持的环境变量：</p>
 * <table>
 *   <tr><th>变量名</th><th>必须</th><th>默认值</th><th>说明</th></tr>
 *   <tr><td>OPENAI_API_KEY</td><td>是</td><td>-</td><td>API 密钥</td></tr>
 *   <tr><td>OPENAI_BASE_URL</td><td>否</td><td>https://api.openai.com/v1</td><td>API 地址</td></tr>
 *   <tr><td>GREENSAM_MODEL</td><td>否</td><td>gpt-4o</td><td>模型名称</td></tr>
 *   <tr><td>GREENSAM_SYSTEM_PROMPT</td><td>否</td><td>内置提示词</td><td>系统提示词</td></tr>
 * </table>
 */
public class AppConfig {

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final String systemPrompt;

    public AppConfig() {
        this.apiKey = requireEnv("OPENAI_API_KEY");
        this.baseUrl = getEnv("OPENAI_BASE_URL", "https://api.openai.com/v1");
        this.model = getEnv("GREENSAM_MODEL", "gpt-4o");
        this.systemPrompt = getEnv("GREENSAM_SYSTEM_PROMPT",
                "You are a helpful CLI assistant with access to file tools. Be concise and helpful.");
    }

    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }

    /**
     * 读取必须的环境变量，缺失时抛出异常。
     * 在应用启动时快速失败（fail-fast），避免运行到一半才发现缺少配置。
     */
    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value.trim();
    }

    /** 读取可选的环境变量，缺失时使用默认值 */
    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}
