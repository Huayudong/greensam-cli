package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greensamcli.client.ChatClient;
import com.greensamcli.client.StreamCallback;
import com.greensamcli.client.StreamingChatClient;
import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatResponse;
import com.greensamcli.model.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Agent Loop——整个 CLI Agent 的核心引擎。
 *
 * <p>实现了 Agent 的本质：一个持续推理的循环。每次循环中，LLM 可以选择：</p>
 * <ol>
 *   <li>直接回复文本（循环结束，返回给用户）</li>
 *   <li>调用工具（执行工具后，把结果追加到对话历史，重新发送给 LLM 继续推理）</li>
 * </ol>
 *
 * <h3>核心循环流程</h3>
 * <pre>
 * ① 用户输入 → 追加到 conversationHistory
 *        │
 * ② 将完整历史 + 工具定义发送给 LLM API
 *        │
 * ③ 解析 LLM 响应
 *        │
 *        ├── 包含 tool_calls？
 *        │       │ 是
 *        │       ▼
 *        │   ④ 将 assistant 消息（含 tool_calls）追加到历史
 *        │       │
 *        │   ⑤ 逐个执行工具：
 *        │       for each tool_call:（对每个 tool_call:）
 *        │           a. 通知 listener（终端显示）
 *        │           b. 通过 ToolRegistry 查找并执行工具
 *        │           c. 将工具结果作为 role="tool" 消息追加到历史
 *        │       │
 *        │   ⑥ 回到步骤 ②（带着工具结果重新发送给 LLM）
 *        │
 *        └── 不包含 tool_calls？
 *                │ 是
 *                ▼
 *            ⑦ 将 assistant 文本消息追加到历史，返回给用户
 * </pre>
 *
 * <h3>安全保障</h3>
 * <p>设置 {@code MAX_ITERATIONS = 20} 防止无限循环。LLM 有可能陷入
 * "调用工具 A → 结果触发工具 B → 结果又触发工具 A" 的死循环。
 * 达到上限后抛出 {@link AgentLoopException}。</p>
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li>{@link #run} — 同步模式：等待 LLM 完整响应后才继续</li>
 *   <li>{@link #runStreaming} — 流式模式：LLM 的文本增量实时回调，工具执行仍为同步</li>
 * </ul>
 */
@Slf4j
public class AgentLoop {

    /**
     * 最大循环次数，防止 LLM 陷入无限工具调用循环
     */
    private static final int MAX_ITERATIONS = 20;

    /**
     * 同步 API 客户端，用于 run() 方法
     */
    private final ChatClient client;

    /**
     * 流式 API 客户端，用于 runStreaming() 方法。可以为 null
     */
    private final StreamingChatClient streamingClient;

    /**
     * 工具注册表，提供工具查找和执行能力
     */
    private final ToolRegistry toolRegistry;

    /**
     * JSON 解析器，用于将 tool_call 的 arguments 字符串解析为 JsonNode
     */
    private final ObjectMapper objectMapper;

    /**
     * 系统提示词，在整个对话中只发送一次（放在 messages 数组最前面）
     */
    private final String systemPrompt;

    /**
     * 完整的对话历史，贯穿整个会话。
     * 包含所有角色的消息：system → user → assistant → tool → assistant → ...
     * 每次 API 调用都会发送完整历史，让 LLM 拥有完整上下文。
     */
    private final List<ChatMessage> conversationHistory;

    /**
     * 同步模式构造函数（不使用流式）
     */
    public AgentLoop(ChatClient client, ToolRegistry toolRegistry,
                     ObjectMapper objectMapper, String systemPrompt) {
        this(client, null, toolRegistry, objectMapper, systemPrompt);
    }

    /**
     * 完整构造函数，同时支持同步和流式模式
     */
    public AgentLoop(ChatClient client, StreamingChatClient streamingClient,
                     ToolRegistry toolRegistry, ObjectMapper objectMapper, String systemPrompt) {
        this.client = client;
        this.streamingClient = streamingClient;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.systemPrompt = systemPrompt;
        this.conversationHistory = new ArrayList<>();
    }

    /**
     * 同步模式：运行一次 Agent 循环。
     * 等待 LLM 完整响应后才返回，适合不需要实时显示的场景。
     *
     * @param userInput 用户在终端输入的文本
     * @param listener  工具调用事件监听器，用于终端显示；传 null 则不通知
     * @return LLM 的最终文本回复（所有工具调用均已完成后）
     */
    public ChatMessage run(String userInput, ToolCallListener listener) {
        // 首次调用时插入系统提示词（只在对话开始时插入一次）
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ChatMessage.system(systemPrompt));
        }

        // 将用户输入追加到历史
        conversationHistory.add(ChatMessage.user(userInput));

        return executeLoop(listener);
    }

    /**
     * 流式模式：运行一次 Agent 循环。
     * LLM 的文本增量通过 streamCallback 实时回调（打字机效果），
     * 工具调用仍为同步执行。
     *
     * @param userInput      用户输入
     * @param listener       工具调用事件监听器
     * @param streamCallback 流式文本回调，每收到一段文本增量就触发
     */
    public void runStreaming(String userInput, ToolCallListener listener, StreamCallback streamCallback) {
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ChatMessage.system(systemPrompt));
        }

        conversationHistory.add(ChatMessage.user(userInput));

        executeLoopStreaming(listener, streamCallback);
    }

    /**
     * 流式 Agent 循环的核心实现。
     *
     * <p>与同步版本的区别在于调用 LLM 的方式：
     * 使用 StreamingChatClient，通过 CountDownLatch 等待流式传输完成后才继续。
     * 工具执行逻辑与同步版本完全一致。</p>
     */
    private void executeLoopStreaming(ToolCallListener listener, StreamCallback streamCallback) {
        // 如果没有流式客户端，降级为同步模式
        if (streamingClient == null) {
            ChatMessage result = executeLoop(listener);
            streamCallback.onComplete(result);
            return;
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("Streaming agent loop iteration {}", i + 1);

            // CountDownLatch 用于等待流式传输完成
            // 因为 streamingClient.sendStreaming 是同步方法但内部通过回调通知，
            // 需要用 latch 阻塞直到 onComplete 或 onError 触发
            CountDownLatch latch = new CountDownLatch(1);
            ChatMessage[] finalMessage = {null};
            Exception[] error = {null};

            // 代理回调：将文本增量转发给外部 streamCallback，
            // 同时在 onComplete/onError 时释放 latch
            streamingClient.sendStreaming(
                    conversationHistory,
                    toolRegistry.getAllDefinitions(),
                    new StreamCallback() {
                        @Override
                        public void onContentDelta(String delta) {
                            streamCallback.onContentDelta(delta);
                        }

                        @Override
                        public void onToolCallDelta(ToolCall toolCall) {
                            streamCallback.onToolCallDelta(toolCall);
                        }

                        @Override
                        public void onComplete(ChatMessage fullAssistantMessage) {
                            finalMessage[0] = fullAssistantMessage;
                            latch.countDown();
                        }

                        @Override
                        public void onError(Exception e) {
                            error[0] = e;
                            latch.countDown();
                        }
                    }
            );

            // 阻塞等待流式传输完成
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentLoopException("Interrupted during streaming");
            }

            if (error[0] != null) {
                throw new AgentLoopException("Streaming error: " + error[0].getMessage());
            }

            ChatMessage assistantMessage = finalMessage[0];
            if (assistantMessage == null) {
                throw new AgentLoopException("No assistant message in streaming response");
            }

            // --- 以下逻辑与同步版本 executeLoop 完全一致 ---

            // 判断：LLM 是直接回复文本，还是请求调用工具？
            if (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty()) {
                // 直接回复文本 → 循环结束
                conversationHistory.add(assistantMessage);
                streamCallback.onComplete(assistantMessage);
                return;
            }

            // 有 tool_calls → 执行工具后继续循环
            conversationHistory.add(assistantMessage);
            executeTools(assistantMessage.getToolCalls(), listener);
            // 继续下一次循环，带着工具结果重新发送给 LLM
        }

        throw new AgentLoopException("Max iterations (" + MAX_ITERATIONS + ") exceeded");
    }

    /**
     * 同步 Agent 循环的核心实现。
     */
    private ChatMessage executeLoop(ToolCallListener listener) {
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("Agent loop iteration {}", i + 1);

            // ① 发送完整对话历史 + 工具定义给 LLM
            ChatResponse response = client.send(
                    conversationHistory,
                    toolRegistry.getAllDefinitions()
            );

            ChatMessage assistantMessage = response.getAssistantMessage();
            if (assistantMessage == null) {
                throw new AgentLoopException("No assistant message in response");
            }

            // ② 判断：LLM 是直接回复文本，还是请求调用工具？
            if (!response.hasToolCalls()) {
                // 直接回复文本 → 追加到历史，循环结束
                conversationHistory.add(assistantMessage);
                return assistantMessage;
            }

            // ③ LLM 请求调用工具 → 先将 assistant 消息（含 tool_calls）追加到历史
            conversationHistory.add(assistantMessage);

            // ④ 执行所有工具调用
            executeTools(assistantMessage.getToolCalls(), listener);

            // ⑤ 回到循环顶部，带着工具结果重新发送给 LLM
        }

        throw new AgentLoopException("Max iterations (" + MAX_ITERATIONS + ") exceeded");
    }

    /**
     * 执行一组工具调用，将结果追加到对话历史。
     *
     * <p>LLM 可以在一次响应中请求调用多个工具（parallel tool calls），
     * 此方法按顺序逐个执行。每个工具的结果（包括错误信息）
     * 都会作为 role="tool" 的消息追加到 conversationHistory。</p>
     *
     * <p>即使某个工具执行失败，也会把错误信息回传给 LLM（而非终止循环），
     * 让 LLM 有机会根据错误信息调整策略（例如换个路径重试）。</p>
     */
    private void executeTools(List<ToolCall> toolCalls, ToolCallListener listener) {
        for (ToolCall toolCall : toolCalls) {
            String toolName = toolCall.getFunction().getName();
            // arguments 是 JSON 字符串，需要解析为 JsonNode 才能取具体字段
            String argumentsStr = toolCall.getFunction().getArguments();

            if (listener != null) {
                listener.onToolCallStarted(toolCall);
            }

            try {
                // 解析参数 JSON 字符串为结构化数据
                JsonNode arguments = objectMapper.readTree(argumentsStr);
                // 通过注册表查找并执行工具
                String result = toolRegistry.executeTool(toolName, arguments);

                if (listener != null) {
                    listener.onToolCallCompleted(toolName, result);
                }

                // 工具结果追加到历史，tool_call_id 用于匹配请求和结果
                conversationHistory.add(
                        ChatMessage.toolResult(toolCall.getId(), toolName, result)
                );
            } catch (Exception e) {
                // 工具执行失败：不终止循环，将错误信息回传给 LLM
                String errorMsg = "Tool execution failed: " + e.getMessage();
                log.error(errorMsg, e);

                if (listener != null) {
                    listener.onToolCallFailed(toolName, errorMsg);
                }

                // 即使失败也要用 tool_result 消息回复（带 Error 前缀），
                // 否则 LLM 会因为缺少 tool_call_id 对应的结果而报错
                conversationHistory.add(
                        ChatMessage.toolResult(toolCall.getId(), toolName, "Error: " + errorMsg)
                );
            }
        }
    }

    /**
     * 获取对话历史的不可变副本
     */
    public List<ChatMessage> getConversationHistory() {
        return List.copyOf(conversationHistory);
    }

    /**
     * 清空对话历史（/clear 命令使用），下次 run 时会重新插入系统提示词
     */
    public void clearHistory() {
        conversationHistory.clear();
    }

    /**
     * Agent 循环过程中的异常
     */
    public static class AgentLoopException extends RuntimeException {
        public AgentLoopException(String message) {
            super(message);
        }
    }
}
