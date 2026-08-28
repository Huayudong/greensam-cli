package com.greensamcli.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatRequest;
import com.greensamcli.model.ChatResponse;
import com.greensamcli.model.ToolCall;
import com.greensamcli.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 OkHttp 的 OpenAI Chat Completions API 流式（SSE）客户端。
 *
 * <p>与 {@link OpenAiChatClient} 的区别：请求体多一个 {@code "stream": true} 参数，
 * API 返回的不是一次性 JSON，而是 SSE（Server-Sent Events）事件流。</p>
 *
 * <p>SSE 协议格式——每行以 {@code "data: "} 开头，最后一个事件是 {@code "data: [DONE]"}：</p>
 *
 * <pre>
 * data: {"choices":[{"delta":{"content":"你"}}]}
 * data: {"choices":[{"delta":{"content":"好"}}]}
 * data: {"choices":[{"delta":{"content":"！"}}]}
 * data: [DONE]
 * </pre>
 *
 * <p>核心处理逻辑：</p>
 * <ol>
 *   <li>逐行读取响应体，过滤出 {@code "data: "} 开头的行</li>
 *   <li>解析每个事件中的 delta（增量内容），分为两种：
 *     <ul>
 *       <li>{@code delta.content} — 文本增量，直接传给 StreamCallback.onContentDelta()</li>
 *       <li>{@code delta.tool_calls} — 工具调用增量，需要逐步拼接（name、arguments 是分段传来的）</li>
 *     </ul>
 *   </li>
 *   <li>遇到 {@code "data: [DONE]"} 时流结束，拼接完整的 assistant 消息并触发 onComplete()</li>
 * </ol>
 *
 * <p><b>工具调用的增量拼接</b>是最复杂的部分：LLM 返回工具调用时，
 * id 和 function.name 只在第一个 delta 中出现，
 * 而 function.arguments 会分成多个 delta 逐步传来（因为参数可能很长），
 * 需要用 index 字段维护一个 List 来逐步累积。
 * 累积遵循协议级统一规则：非空值才写入，空值（字段缺省 / null / 空串）一律忽略，
 * 保证对任何 OpenAI 兼容服务端都不需要按厂商适配。</p>
 */
@Slf4j
public class OpenAiStreamingChatClient implements StreamingChatClient {

    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public OpenAiStreamingChatClient(OkHttpClient httpClient, ObjectMapper objectMapper,
                                     String apiKey, String baseUrl, String model) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public void sendStreaming(List<ChatMessage> messages, List<ToolDefinition> tools,
                              StreamCallback callback) {
        // 构建请求体
        ChatRequest request = ChatRequest.builder()
                .model(model)
                .messages(messages)
                .tools(tools != null && tools.isEmpty() ? null : tools)
                .build();

        // 将 ChatRequest 转为 JSON 树，手动添加 "stream": true 与
        // "stream_options": {"include_usage": true}（后者让服务端在流末尾附带
        // 一个不含 choices、只含 usage 的统计事件；不支持的兼容服务端会忽略或报错）
        // 这样做是因为 ChatRequest 模型是同步/流式共用的，不需要单独加流式字段
        String requestBody;
        try {
            JsonNode requestNode = objectMapper.valueToTree(request);
            ((ObjectNode) requestNode).put("stream", true);
            ((ObjectNode) requestNode).putObject("stream_options").put("include_usage", true);
            requestBody = objectMapper.writeValueAsString(requestNode);
        } catch (Exception e) {
            callback.onError(e);
            return;
        }

        // 构建 API 端点 URL
        HttpUrl url = HttpUrl.parse(baseUrl);
        if (url == null) {
            callback.onError(new IllegalArgumentException("非法的 base URL: " + baseUrl));
            return;
        }
        HttpUrl endpoint = url.newBuilder()
                .addPathSegment("chat")
                .addPathSegment("completions")
                .build();

        Request httpRequest = new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .build();

        // 累积器：把所有 delta 拼接成完整的响应
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        // 内联 <think> 标签过滤器：GLM 系端点把思考过程混在 content 里传输，
        // 需剥离后路由到 reasoning 回调，避免思考原文进对话历史
        ThinkContentFilter thinkFilter = new ThinkContentFilter();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                callback.onError(new ChatClientException(
                        "API 返回 " + response.code() + ": " + body));
                return;
            }

            if (response.body() == null) {
                callback.onError(new ChatClientException("响应体为空"));
                return;
            }

            // 逐行读取 SSE 事件流
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));

            String line;
            while ((line = reader.readLine()) != null) {
                // SSE 事件以 "data:" 开头（冒号后带不带空格均符合 SSE 规范，
                // 部分兼容网关会发 "data:{...}" 紧凑形式），其他行（空行、注释行）跳过
                if (!line.startsWith("data:")) {
                    continue;
                }

                String data = line.substring(5).trim();
                // [DONE] 是流结束标记
                if ("[DONE]".equals(data)) {
                    break;
                }

                try {
                    JsonNode chunk = objectMapper.readTree(data);

                    // usage 统计事件：流末尾的独立 chunk，不含 choices，
                    // 必须在 choices 判空之前处理（否则会被当空事件跳过）
                    if (chunk.has("usage") && !chunk.get("usage").isNull()) {
                        JsonNode usageNode = chunk.get("usage");
                        callback.onUsage(new ChatResponse.Usage(
                                usageNode.path("prompt_tokens").asInt(0),
                                usageNode.path("completion_tokens").asInt(0),
                                usageNode.path("total_tokens").asInt(0)));
                    }

                    JsonNode choices = chunk.get("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }

                    // delta 是本次事件的增量内容（区别于完整 message）
                    JsonNode delta = choices.get(0).get("delta");

                    // 处理推理增量：推理型模型（GLM、DeepSeek 等）先输出思考过程，
                    // 字段名为 reasoning_content；普通模型无此字段，不触发回调
                    if (delta != null && delta.has("reasoning_content")
                            && !delta.get("reasoning_content").isNull()) {
                        callback.onReasoningDelta(delta.get("reasoning_content").asText());
                    }

                    // 处理文本增量：LLM 生成的文字一段一段传来。
                    // 先过内联 think 过滤器：思考段路由到 onReasoningDelta，
                    // 只有剥离后的正文才累计进最终消息并触发 onContentDelta
                    if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                        String contentDelta = delta.get("content").asText();
                        thinkFilter.feed(contentDelta,
                                s -> {
                                    contentBuilder.append(s);
                                    callback.onContentDelta(s);
                                },
                                callback::onReasoningDelta);
                    }

                    // 处理工具调用增量：比文本更复杂，需要用 index 逐步拼接
                    if (delta != null && delta.has("tool_calls")) {
                        JsonNode toolCallsDelta = delta.get("tool_calls");
                        for (JsonNode tcDelta : toolCallsDelta) {
                            // index 标识这是第几个工具调用（LLM 可以在一次响应中调用多个工具）
                            int index = tcDelta.has("index") ? tcDelta.get("index").asInt() : 0;

                            // 确保 toolCalls 列表足够长，不足的位置补空 ToolCall
                            while (toolCalls.size() <= index) {
                                toolCalls.add(ToolCall.builder()
                                        .function(new ToolCall.FunctionCall("", ""))
                                        .build());
                            }

                            ToolCall existing = toolCalls.get(index);

                            // 增量累积的统一规则：id / type / function.name 只在首个分片出现，
                            // 后续分片中这些字段可能缺省、为 null 或重复下发空串，
                            // 一律忽略——只有非空值才写入，绝不用空值覆盖已累积的真实值，
                            // 不依赖任何特定服务端的分片习惯
                            String id = tcDelta.path("id").asText("");
                            if (!id.isEmpty()) {
                                existing.setId(id);
                            }
                            String type = tcDelta.path("type").asText("");
                            if (!type.isEmpty()) {
                                existing.setType(type);
                            }

                            // function.arguments 是唯一允许跨分片逐步追加的字段
                            if (tcDelta.has("function")) {
                                JsonNode fn = tcDelta.get("function");
                                String name = fn.path("name").asText("");
                                if (!name.isEmpty()) {
                                    existing.getFunction().setName(name);
                                }
                                String argumentFragment = fn.path("arguments").asText("");
                                if (!argumentFragment.isEmpty()) {
                                    // arguments 是逐步拼接的，新的片段追加到已有内容后面
                                    String currentArgs = existing.getFunction().getArguments();
                                    existing.getFunction().setArguments(
                                            currentArgs + argumentFragment);
                                }
                            }

                            callback.onToolCallDelta(existing);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse SSE chunk: {}", data, e);
                }
            }

            // 流收尾：把过滤器缓冲中跨增量的残留投递出去（可能命中流末尾的思考段尾部）
            thinkFilter.flush(
                    s -> {
                        contentBuilder.append(s);
                        callback.onContentDelta(s);
                    },
                    callback::onReasoningDelta);

            // 流结束，拼接完整的 assistant 消息
            String finalContent = contentBuilder.length() > 0 ? contentBuilder.toString() : null;
            List<ToolCall> finalToolCalls = toolCalls.isEmpty() ? null : toolCalls;

            ChatMessage assistantMessage;
            if (finalToolCalls != null) {
                // LLM 请求调用工具
                assistantMessage = ChatMessage.assistantWithToolCalls(finalToolCalls);
            } else {
                // LLM 返回纯文本
                assistantMessage = ChatMessage.assistant(finalContent);
            }

            callback.onComplete(assistantMessage);

        } catch (IOException e) {
            callback.onError(e);
        }
    }

    /**
     * API 调用过程中的运行时异常
     */
    public static class ChatClientException extends RuntimeException {
        public ChatClientException(String message) {
            super(message);
        }
    }
}
