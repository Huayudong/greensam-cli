package com.greensamcli.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatRequest;
import com.greensamcli.model.ChatResponse;
import com.greensamcli.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.List;

/**
 * 基于 OkHttp 的 OpenAI Chat Completions API 同步客户端。
 *
 * <p>职责：将 Java 对象（ChatRequest）序列化为 JSON → 发送 HTTP POST → 解析 JSON 响应为 ChatResponse。</p>
 *
 * <p>调用流程：</p>
 * <ol>
 *   <li>构建 ChatRequest（model + messages + tools）</li>
 *   <li>Jackson 序列化为 JSON 字符串</li>
 *   <li>OkHttp 发送 POST 请求到 {@code {baseUrl}/chat/completions}</li>
 *   <li>添加 Authorization: Bearer {apiKey} 头</li>
 *   <li>解析响应 JSON 为 ChatResponse 对象</li>
 * </ol>
 *
 * <p>baseUrl 默认为 {@code https://api.openai.com/v1}，可以通过环境变量修改，
 * 用于对接兼容 OpenAI 格式的第三方 API（如 Azure OpenAI、本地部署的模型等）。</p>
 */
@Slf4j
public class OpenAiChatClient implements ChatClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public OpenAiChatClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                            String apiKey, String baseUrl, String model) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public ChatResponse send(List<ChatMessage> messages, List<ToolDefinition> tools) {
        // 构建请求体：如果 tools 为空列表则不发送（传 null 会被 @JsonInclude 跳过）
        ChatRequest request = ChatRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools != null && tools.isEmpty() ? null : tools)
                .build();

        // 序列化为 JSON
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new ChatClientException("Failed to serialize request", e);
        }

        log.debug("Sending request to {}/chat/completions", baseUrl);

        // 拼接完整的 API 端点 URL
        HttpUrl url = HttpUrl.parse(baseUrl);
        if (url == null) {
            throw new ChatClientException("Invalid base URL: " + baseUrl);
        }
        HttpUrl endpoint = url.newBuilder()
                .addPathSegment("chat")
                .addPathSegment("completions")
                .build();

        // 构建 HTTP 请求，添加认证头
        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .build();

        // 执行请求并解析响应
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            // 处理 HTTP 错误（401 认证失败、429 限流、500 服务端错误等）
            if (!response.isSuccessful()) {
                log.error("API error: {} - {}", response.code(), responseBody);
                throw new ChatClientException(
                        "API returned " + response.code() + ": " + responseBody);
            }

            // 将 JSON 响应反序列化为 ChatResponse 对象
            return objectMapper.readValue(responseBody, ChatResponse.class);
        } catch (IOException e) {
            throw new ChatClientException("Request failed", e);
        }
    }

    /**
     * API 调用过程中的运行时异常
     */
    public static class ChatClientException extends RuntimeException {
        public ChatClientException(String message) {
            super(message);
        }

        public ChatClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
