package com.greensamcli.agent;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ToolCall;
import com.greensamcli.utils.ContextTokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextTruncator} 截断策略测试。
 *
 * <p>对齐批次③验收标准的边界：恰好阈值不截断、system 保护、只剩最后一轮
 * 不丢弃、按完整轮丢弃不产生孤儿 tool 消息、空历史安全、入参不被修改。</p>
 *
 * @author Macro Ray
 * @since 2026-09-18
 */
class ContextTruncatorTest {

    /**
     * 构造一段两轮对话：system + 第一轮（user + assistant）+ 第二轮（user + assistant）
     */
    private List<ChatMessage> twoRounds(String firstReply, String secondReply) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("test system prompt"));
        history.add(ChatMessage.user("第一问"));
        history.add(ChatMessage.assistant(firstReply));
        history.add(ChatMessage.user("第二问"));
        history.add(ChatMessage.assistant(secondReply));
        return history;
    }

    @Test
    void 未超限_原样返回且零丢弃() {
        List<ChatMessage> history = twoRounds("回复一", "回复二");
        long maxTokens = ContextTokenEstimator.estimateMessages(history) + 1;

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(0, result.droppedCount());
        assertSame(history, result.keptMessages(), "未截断时应返回原列表");
    }

    @Test
    void 恰好等于阈值_不截断() {
        List<ChatMessage> history = twoRounds("回复一", "回复二");
        long maxTokens = ContextTokenEstimator.estimateMessages(history);

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(0, result.droppedCount());
        assertEquals(5, result.keptMessages().size());
    }

    @Test
    void 超限_丢弃最旧一整轮_保留system与最后一轮() {
        List<ChatMessage> history = twoRounds("回".repeat(2000), "回复二");
        // 阈值恰好容纳 system + 最后一轮：发送前必超限，且第一轮被丢后恰好停手
        long maxTokens = ContextTokenEstimator.estimateMessages(List.of(
                history.get(0), history.get(3), history.get(4)));

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(2, result.droppedCount());
        assertEquals(3, result.keptMessages().size());
        assertEquals("system", result.keptMessages().get(0).getRole());
        assertEquals("第二问", result.keptMessages().get(1).getContent());
        assertEquals("回复二", result.keptMessages().get(2).getContent());
    }

    @Test
    void 超限多轮_逐轮丢弃直到回落阈值() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("test"));
        for (int round = 1; round <= 3; round++) {
            history.add(ChatMessage.user("问" + round));
            history.add(ChatMessage.assistant("答" + round));
        }
        long maxTokens = ContextTokenEstimator.estimateMessages(List.of(
                history.get(0), history.get(5), history.get(6)));

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(4, result.droppedCount());
        assertEquals(3, result.keptMessages().size());
        assertEquals("问3", result.keptMessages().get(1).getContent());
        assertEquals("答3", result.keptMessages().get(2).getContent());
    }

    @Test
    void 只剩最后一轮_即使超限也不丢弃() {
        // 单轮对话：第一轮即最后一轮，没有可安全丢弃的完整轮
        List<ChatMessage> history = List.of(
                ChatMessage.system("test system prompt"),
                ChatMessage.user("第一问"),
                ChatMessage.assistant("回复一"));
        // 阈值比全量少 1：必然超限，但当前任务上下文不可拆，只能原样保留
        long maxTokens = ContextTokenEstimator.estimateMessages(history) - 1;

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(0, result.droppedCount());
        assertEquals(3, result.keptMessages().size());
    }

    @Test
    void 删完可删轮次后仍超限_保留最后一轮放行() {
        // 两轮且第一轮内容很小：阈值低于截断后估算——第一轮删完后仍超限，
        // 第二轮是最后一轮不可再拆，带着超限风险放行
        List<ChatMessage> history = twoRounds("回复一", "回复二");
        long maxTokens = ContextTokenEstimator.estimateMessages(history) - 1;

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(2, result.droppedCount());
        assertEquals(3, result.keptMessages().size());
    }

    @Test
    void 只有system超限_不丢弃() {
        List<ChatMessage> history = List.of(ChatMessage.system("s".repeat(1000)));
        long maxTokens = ContextTokenEstimator.estimateMessages(history) - 1;

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(0, result.droppedCount());
        assertEquals(1, result.keptMessages().size());
    }

    @Test
    void 丢弃以轮为单位_不产生孤儿tool消息() {
        // 第一轮含工具调用对：user1 + assistant(tool_calls) + tool 结果
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("test"));
        history.add(ChatMessage.user("第一问"));
        history.add(ChatMessage.assistantWithToolCalls(List.of(
                ToolCall.builder().id("call_1").type("function")
                        .function(new ToolCall.FunctionCall("read_file", "{}"))
                        .build())));
        history.add(ChatMessage.toolResult("call_1", "read_file", "回".repeat(2000)));
        history.add(ChatMessage.user("第二问"));
        history.add(ChatMessage.assistant("回复二"));
        long maxTokens = ContextTokenEstimator.estimateMessages(List.of(
                history.get(0), history.get(4), history.get(5)));

        ContextTruncator.TruncateResult result = ContextTruncator.truncate(history, maxTokens);

        assertEquals(3, result.droppedCount());
        // 截断后历史中不允许残留任何 tool 结果消息（其配对的 tool_call 已随轮丢弃）
        assertTrue(result.keptMessages().stream().noneMatch(m -> "tool".equals(m.getRole())),
                "截断不得产生孤儿 tool 消息");
        assertEquals("第二问", result.keptMessages().get(1).getContent());
    }

    @Test
    void 空历史与null_安全返回() {
        assertEquals(0, ContextTruncator.truncate(null, 100).droppedCount());
        assertEquals(0, ContextTruncator.truncate(List.of(), 100).droppedCount());
    }

    @Test
    void 纯函数_不修改入参列表() {
        List<ChatMessage> history = new ArrayList<>(twoRounds("回".repeat(2000), "回复二"));
        int originalSize = history.size();
        long maxTokens = ContextTokenEstimator.estimateMessages(List.of(
                history.get(0), history.get(3), history.get(4)));

        ContextTruncator.truncate(history, maxTokens);

        assertEquals(originalSize, history.size(), "截断器不得原地修改入参");
    }
}
