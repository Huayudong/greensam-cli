package com.greensamcli.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TerminalRenderer} 行纪律与 emoji 语义测试。
 *
 * <p>核心约定：一个 emoji 代表系统的一类反应且独占一行（块）——
 * 工具调用统一 🛠️；独立行事件（🛠️/✅/❌）打印前先收掉未关闭的流式块；
 * 工具结果换行折叠为 ⏎，绝不输出字面 \n；回答块开启时剥掉前导换行。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
class TerminalRendererTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private TerminalRenderer renderer;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        renderer = new TerminalRenderer();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void displayToolCall_所有工具统一使用工具emoji() {
        renderer.displayToolCall("read_file", "{\"path\":\"a.txt\"}");
        renderer.displayToolCall("some_future_tool", "{}");

        String out = output();
        assertTrue(out.contains("🛠️ 调用 read_file({\"path\":\"a.txt\"})"));
        assertTrue(out.contains("🛠️ 调用 some_future_tool({})"));
        // 不得再出现按工具分派的旧 emoji
        assertFalse(out.contains("📖"));
        assertFalse(out.contains("⚙️"));
    }

    @Test
    void 流式回答块未关闭时_工具调用先收块并另起一行() {
        renderer.displayAssistantDelta("正在处理");

        renderer.displayToolCall("read_file", "{}");

        // 叙述与工具行不得粘连在同一行
        String out = output();
        assertFalse(out.contains("正在处理" + "  🛠️"));
        assertTrue(out.contains("\033[36m正在处理\033[0m\n"));
        assertTrue(out.contains("\n\033[33m  🛠️ 调用 read_file({})\033[0m"));
    }

    @Test
    void displayToolResult_换行折叠_不输出字面n() {
        renderer.displayToolResult("execute_command", "exit_code=0\n\nstdout:\n1\n2");

        String out = output();
        assertFalse(out.contains("\\n"), "不得输出字面反斜杠n");
        assertTrue(out.contains("✅ execute_command: exit_code=0⏎stdout:⏎1⏎2"));
    }

    @Test
    void summarizeResult_超长截断并标注原始字符数() {
        String result = "a".repeat(300) + "\n" + "b".repeat(50);

        String summary = TerminalRenderer.summarizeResult(result);

        assertEquals(200 + "…（共 351 字符）".length(), summary.length());
        assertTrue(summary.endsWith("…（共 351 字符）"));
        assertFalse(summary.contains("\n"));
    }

    @Test
    void summarizeResult_空结果() {
        assertEquals("(空)", TerminalRenderer.summarizeResult(null));
        assertEquals("(空)", TerminalRenderer.summarizeResult(""));
    }

    @Test
    void displayAssistantDelta_回答块开启时剥掉前导换行() {
        // MiniMax 等端点在 </think> 后会紧跟 "\n\n" 才输出正文
        renderer.displayAssistantDelta("\n\n你好");

        String out = output();
        assertTrue(out.startsWith("🤖 \033[36m你好"),
                "块首不得悬挂空行，实际输出：" + out.replace("\n", "\\n"));
    }

    @Test
    void displayAssistantDelta_纯换行增量不空开回答块() {
        renderer.displayAssistantDelta("\n\n");

        assertEquals("", output(), "纯换行的首个增量不应开启空的回答块");
    }

    @Test
    void displayReasoningDelta_回答块未关闭时先收掉再开思考块() {
        renderer.displayAssistantDelta("部分回答");
        renderer.displayReasoningDelta("开始思考");

        String out = output();
        // 思考块必须从新行开始，不得粘在回答文字后
        assertTrue(out.contains("部分回答\033[0m\n\n💭 \033[90;3m开始思考"),
                "实际输出：" + out.replace("\n", "\\n"));
    }
}
