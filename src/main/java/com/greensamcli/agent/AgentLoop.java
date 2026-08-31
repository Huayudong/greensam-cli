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
import java.util.concurrent.atomic.AtomicBoolean;

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
 *
 * <h3>中断机制</h3>
 * <p>AgentLoop 对「中断」只感知一个抽象事实：「循环可能被取消」。
 * 信号来源（如 Repl 的 Ctrl+C 接线）在循环之外：外部线程调用 {@link #cancel()}，
 * 循环在各安全点（发送 LLM 前后、执行每个工具前）检查取消标志，
 * 命中即抛出 {@link AgentCancelledException} 终止当前回合。</p>
 * <p>取消保证对话历史结构合法：本轮 assistant 发起的每个 tool_call
 * 都会补上「用户已中断」结果（OpenAI 协议要求一一对应），
 * 因此中断后可以直接继续提问。</p>
 */
@Slf4j
public class AgentLoop {

    /**
     * 最大循环次数，防止 LLM 陷入无限工具调用循环
     */
    private static final int MAX_ITERATIONS = 20;
    /**
     * 用户中断时为未执行 tool_call 补写的占位结果文本
     */
    private static final String INTERRUPTED_TOOL_RESULT = "用户已中断本轮执行，该工具调用未执行。";
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
     * 取消标志：cancel() 置位，回合开始/结束时复位。
     * 只用标志判断取消，中断（interrupt）仅作为唤醒阻塞操作的信号
     */
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /**
     * 当前执行回合的线程；null 表示空闲（无进行中的回合）。
     * cancel() 借助它把中断信号送达阻塞中的操作（流式 latch 等待、子进程 waitFor）
     */
    private volatile Thread roundThread;

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
     * 请求中断当前回合（幂等，线程安全，可从任意线程调用）。
     *
     * <p>置位取消标志并打断回合线程：阻塞在流式 latch 等待、子进程
     * {@code waitFor} 上的操作会立刻感知；不可中断的阻塞（如非流式 HTTP 读）
     * 则在响应返回后的下一个安全点丢弃结果。空闲（无进行中的回合）时为无操作，
     * 不影响后续回合。</p>
     */
    public void cancel() {
        Thread thread = roundThread;
        if (thread == null) {
            log.debug("收到取消请求，但当前无进行中的回合，忽略");
            return;
        }
        log.info("收到取消请求，正在中断当前回合");
        cancelRequested.set(true);
        thread.interrupt();
    }

    /**
     * 回合开始：记录回合线程并复位取消标志
     */
    private void beginRound() {
        cancelRequested.set(false);
        roundThread = Thread.currentThread();
    }

    /**
     * 回合结束：清空回合线程记录，并清掉可能遗留的中断标志——
     * 避免污染同一物理线程（通常是主线程）后续的阻塞操作（如 JLine 读取输入）
     */
    private void endRound() {
        roundThread = null;
        Thread.interrupted();
    }

    /**
     * 取消安全点：已取消则抛出 {@link AgentCancelledException} 终止回合
     */
    private void checkCancelled() {
        if (cancelRequested.get()) {
            log.info("回合已被用户取消，停止执行");
            throw new AgentCancelledException();
        }
    }

    /**
     * 同步模式：运行一次 Agent 循环。
     * 等待 LLM 完整响应后才返回，适合不需要实时显示的场景。
     *
     * @param userInput 用户在终端输入的文本
     * @param listener  工具调用事件监听器，用于终端显示；传 null 则不通知
     * @return LLM 的最终文本回复（所有工具调用均已完成后）
     * @throws AgentCancelledException 用户在回合期间取消
     */
    public ChatMessage run(String userInput, ToolCallListener listener) {
        beginRound();
        try {
            // 首次调用时插入系统提示词（只在对话开始时插入一次）
            if (conversationHistory.isEmpty()) {
                conversationHistory.add(ChatMessage.system(systemPrompt));
            }

            // 将用户输入追加到历史
            conversationHistory.add(ChatMessage.user(userInput));

            return executeLoop(listener);
        } finally {
            endRound();
        }
    }

    /**
     * 流式模式：运行一次 Agent 循环。
     * LLM 的文本增量通过 streamCallback 实时回调（打字机效果），
     * 工具调用仍为同步执行。
     *
     * @param userInput      用户输入
     * @param listener       工具调用事件监听器
     * @param streamCallback 流式文本回调，每收到一段文本增量就触发
     * @throws AgentCancelledException 用户在回合期间取消
     */
    public void runStreaming(String userInput, ToolCallListener listener, StreamCallback streamCallback) {
        beginRound();
        try {
            if (conversationHistory.isEmpty()) {
                conversationHistory.add(ChatMessage.system(systemPrompt));
            }

            conversationHistory.add(ChatMessage.user(userInput));

            executeLoopStreaming(listener, streamCallback);
        } finally {
            endRound();
        }
    }

    /**
     * 流式 Agent 循环的核心实现。
     *
     * <p>与同步版本的区别在于调用 LLM 的方式：SSE 读取在独立守护线程中执行，
     * 本线程阻塞在 {@link CountDownLatch} 上等待流式传输完成后才继续。
     * 把读取挪出本线程的原因：SSE 是普通阻塞 socket 读，不响应线程中断；
     * 若直接在本线程读，用户取消时只能等模型生成完整个响应，提示符迟迟不返回。
     * 挪出后本线程的 latch 等待可被 cancel() 的 interrupt 立刻打断。</p>
     * <p>工具执行逻辑与同步版本完全一致。</p>
     */
    private void executeLoopStreaming(ToolCallListener listener, StreamCallback streamCallback) {
        // 如果没有流式客户端，降级为同步模式
        if (streamingClient == null) {
            ChatMessage result = executeLoop(listener);
            streamCallback.onComplete(result);
            return;
        }

        // 本轮（可含多次 LLM 调用）的 token 用量合计，[0]=输入 [1]=输出
        int[] usageTotals = new int[2];

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("Streaming agent loop iteration {}", i + 1);

            // 取消安全点：发送 LLM 前检查
            checkCancelled();

            // CountDownLatch 用于等待流式传输完成
            CountDownLatch latch = new CountDownLatch(1);
            ChatMessage[] finalMessage = {null};
            Exception[] error = {null};
            // 本次 LLM 调用是否已作废：取消后不再向渲染层转发任何事件。
            // 必须用每次迭代独立的标志而非全局 cancelRequested——取消后本回合立即返回
            // 提示符，下一回合会把全局标志复位，而本次的残留流式线程可能还活着，
            // 若它届时仍看全局标志，迟到的增量就会打进新一轮的提示符
            AtomicBoolean discarded = new AtomicBoolean(false);

            // 代理回调：将文本/思考增量转发给外部 streamCallback，
            // token 用量就地累计；已作废后静默丢弃全部事件，onComplete/onError 释放 latch
            Thread streamThread = new Thread(() -> streamingClient.sendStreaming(
                    conversationHistory,
                    toolRegistry.getAllDefinitions(),
                    new StreamCallback() {
                        @Override
                        public void onContentDelta(String delta) {
                            if (discarded.get()) {
                                return;
                            }
                            streamCallback.onContentDelta(delta);
                        }

                        @Override
                        public void onReasoningDelta(String delta) {
                            if (discarded.get()) {
                                return;
                            }
                            streamCallback.onReasoningDelta(delta);
                        }

                        @Override
                        public void onUsage(ChatResponse.Usage usage) {
                            if (discarded.get() || usage == null) {
                                return;
                            }
                            usageTotals[0] += usage.getPromptTokens();
                            usageTotals[1] += usage.getCompletionTokens();
                        }

                        @Override
                        public void onToolCallDelta(ToolCall toolCall) {
                            if (discarded.get()) {
                                return;
                            }
                            streamCallback.onToolCallDelta(toolCall);
                        }

                        @Override
                        public void onComplete(ChatMessage fullAssistantMessage) {
                            if (discarded.get()) {
                                return;
                            }
                            finalMessage[0] = fullAssistantMessage;
                            latch.countDown();
                        }

                        @Override
                        public void onError(Exception e) {
                            if (discarded.get()) {
                                return;
                            }
                            error[0] = e;
                            latch.countDown();
                        }
                    }
            ), "llm-stream-reader");
            // 守护线程：取消后残留的流式读取不阻止 JVM 退出
            streamThread.setDaemon(true);
            streamThread.start();

            // 阻塞等待流式传输完成（可被 cancel() 的中断打断）
            try {
                latch.await();
            } catch (InterruptedException e) {
                discarded.set(true);
                // 中断由取消引起 → 走取消路径；其余情况恢复标志并按错误处理
                checkCancelled();
                Thread.currentThread().interrupt();
                throw new AgentLoopException("流式传输被中断");
            }

            // 取消安全点：流已结束但用户已取消 → 响应作废
            if (cancelRequested.get()) {
                discarded.set(true);
                log.info("流式响应返回时用户已取消，丢弃响应");
                throw new AgentCancelledException();
            }

            if (error[0] != null) {
                // 连接被取消引发的传输错误不当作失败，统一走取消路径
                if (cancelRequested.get()) {
                    discarded.set(true);
                    throw new AgentCancelledException();
                }
                throw new AgentLoopException("流式传输失败: " + error[0].getMessage());
            }

            ChatMessage assistantMessage = finalMessage[0];
            if (assistantMessage == null) {
                throw new AgentLoopException("流式响应中缺少 assistant 消息");
            }

            // --- 以下逻辑与同步版本 executeLoop 完全一致 ---

            // 判断：LLM 是直接回复文本，还是请求调用工具？
            if (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty()) {
                // 直接回复文本 → 循环结束
                conversationHistory.add(assistantMessage);
                notifyRoundUsage(listener, usageTotals[0], usageTotals[1]);
                streamCallback.onComplete(assistantMessage);
                return;
            }

            // 有 tool_calls → 执行工具后继续循环
            conversationHistory.add(assistantMessage);
            executeTools(assistantMessage.getToolCalls(), listener);
            // 继续下一次循环，带着工具结果重新发送给 LLM
        }

        throw new AgentLoopException("已达到最大循环次数（" + MAX_ITERATIONS + "），终止执行");
    }

    /**
     * 同步 Agent 循环的核心实现。
     */
    private ChatMessage executeLoop(ToolCallListener listener) {
        // 本轮（可含多次 LLM 调用）的 token 用量合计
        int roundPromptTokens = 0;
        int roundCompletionTokens = 0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.debug("Agent loop iteration {}", i + 1);

            // ① 取消安全点：发送 LLM 前检查
            checkCancelled();

            // ② 发送完整对话历史 + 工具定义给 LLM
            ChatResponse response;
            try {
                response = client.send(
                        conversationHistory,
                        toolRegistry.getAllDefinitions()
                );
            } catch (Exception e) {
                // 取消打断导致的调用失败（连接中止等）不当作错误，统一走取消路径
                if (cancelRequested.get()) {
                    throw new AgentCancelledException();
                }
                throw e;
            }

            // ③ 取消安全点：响应虽已返回，但用户已取消 →
            //    整条 assistant 消息丢弃、不入历史（含 tool_calls 的消息若无结果
            //    会让对话结构非法），回到提示符
            if (cancelRequested.get()) {
                log.info("LLM 响应返回时用户已取消，丢弃响应");
                throw new AgentCancelledException();
            }

            // 累计本次 LLM 调用的 token 用量
            ChatResponse.Usage usage = response.getUsage();
            if (usage != null) {
                roundPromptTokens += usage.getPromptTokens();
                roundCompletionTokens += usage.getCompletionTokens();
            }

            ChatMessage assistantMessage = response.getAssistantMessage();
            if (assistantMessage == null) {
                throw new AgentLoopException("响应中缺少 assistant 消息");
            }

            // ④ 判断：LLM 是直接回复文本，还是请求调用工具？
            if (!response.hasToolCalls()) {
                // 直接回复文本 → 追加到历史，循环结束
                conversationHistory.add(assistantMessage);
                notifyRoundUsage(listener, roundPromptTokens, roundCompletionTokens);
                return assistantMessage;
            }

            // ⑤ LLM 请求调用工具 → 先将 assistant 消息（含 tool_calls）追加到历史
            conversationHistory.add(assistantMessage);

            // ⑥ 执行所有工具调用
            executeTools(assistantMessage.getToolCalls(), listener);

            // ⑦ 回到循环顶部，带着工具结果重新发送给 LLM
        }

        throw new AgentLoopException("已达到最大循环次数（" + MAX_ITERATIONS + "），终止执行");
    }

    /**
     * 回合结束时通知监听器本轮 token 用量合计。
     * 未拿到任何 usage（服务端未返回）时不通知，避免渲染无意义的 0。
     */
    private void notifyRoundUsage(ToolCallListener listener, int promptTokens, int completionTokens) {
        if (listener == null || (promptTokens <= 0 && completionTokens <= 0)) {
            return;
        }
        listener.onRoundUsage(new ChatResponse.Usage(
                promptTokens, completionTokens, promptTokens + completionTokens));
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
     *
     * <p>用户中途取消时，剩余未执行的 tool_call 会全部补上
     * 「用户已中断」结果再返回（见 {@link #fillInterruptedToolResults}）。</p>
     */
    private void executeTools(List<ToolCall> toolCalls, ToolCallListener listener) {
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);

            // 取消安全点：执行前检查，命中则补齐剩余 tool_call 的占位结果并停止
            if (cancelRequested.get()) {
                fillInterruptedToolResults(toolCalls, i, listener);
                return;
            }

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
                // 工具执行失败：不终止循环，将错误信息回传给 LLM。
                // 控制台仅一条 WARN 行（logback %nopex 已抑制堆栈），完整堆栈落文件；
                // 带 toolName 便于从日志直接回溯到具体工具调用
                String errorMsg = "工具执行失败: " + e.getMessage();
                log.warn("工具执行失败，已回传 LLM 重试决策: tool={}, error={}", toolName, e.getMessage(), e);

                if (listener != null) {
                    listener.onToolCallFailed(toolName, errorMsg);
                }

                // 即使失败也要用 tool_result 消息回复（带错误前缀），
                // 否则 LLM 会因为缺少 tool_call_id 对应的结果而报错
                conversationHistory.add(
                        ChatMessage.toolResult(toolCall.getId(), toolName, "错误: " + errorMsg)
                );
            }
        }
    }

    /**
     * 从 fromIndex 起把所有未执行的 tool_call 补上「用户已中断」占位结果。
     *
     * <p>OpenAI 协议要求 assistant 消息的每个 tool_call 必须有对应的一条
     * tool result，补齐后历史才是合法对话——用户中断后可以直接继续提问
     * （如「刚才做到哪了」），LLM 也能从中知道哪些步骤没执行。</p>
     */
    private void fillInterruptedToolResults(List<ToolCall> toolCalls, int fromIndex,
                                            ToolCallListener listener) {
        for (int i = fromIndex; i < toolCalls.size(); i++) {
            ToolCall toolCall = toolCalls.get(i);
            String toolName = toolCall.getFunction().getName();
            conversationHistory.add(
                    ChatMessage.toolResult(toolCall.getId(), toolName, INTERRUPTED_TOOL_RESULT)
            );
            if (listener != null) {
                listener.onToolCallFailed(toolName, "用户已中断");
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
