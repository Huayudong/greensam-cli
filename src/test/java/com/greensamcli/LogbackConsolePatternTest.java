package com.greensamcli;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * logback 控制台 appender 模式校验——锁定「控制台不输出堆栈」的 REPL 显示契约。
 *
 * <p>控制台 pattern 带 {@code %nopex}：工具失败等可恢复错误在控制台只保留单行消息，
 * 完整堆栈仅落文件（FILE appender）。若 pattern 被无意改回丢失 %nopex，
 * Java 堆栈将重新污染 REPL 对话区，本测试即失败。</p>
 *
 * @author Macro Ray
 * @since 2026-08-28
 */
class LogbackConsolePatternTest {

    /**
     * 从 classpath 上的 logback.xml 提取 CONSOLE appender 的 pattern。
     * 资源文件即测试夹具，避免模式字符串双份维护漂移。
     */
    private String consolePattern() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/logback.xml")) {
            assertNotNull(in, "logback.xml 必须在测试 classpath 上");
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(
                    "<appender name=\"CONSOLE\"[\\s\\S]*?<pattern>(.*?)</pattern>").matcher(xml);
            assertTrue(matcher.find(), "logback.xml 中必须存在 CONSOLE appender 的 pattern");
            return matcher.group(1).trim();
        }
    }

    @Test
    void consolePattern_suppressesStackTraceButKeepsMessage() throws Exception {
        LoggerContext ctx = new LoggerContext();
        PatternLayout layout = new PatternLayout();
        layout.setContext(ctx);
        layout.setPattern(consolePattern());
        layout.start();

        // 模拟 AgentLoop 工具失败的 WARN 事件（带异常，堆栈只允许落文件）
        RuntimeException ex = new RuntimeException("模拟工具执行失败");
        LoggingEvent event = new LoggingEvent(
                getClass().getName(), ctx.getLogger("测试Logger"), Level.WARN,
                "工具执行失败，已回传 LLM 重试决策: tool=read_file, error=文件不存在", ex, null);

        String output = layout.doLayout(event);

        assertTrue(output.contains("工具执行失败，已回传 LLM 重试决策"), "消息本身必须在控制台可见");
        assertFalse(output.contains("at "), "控制台不得输出堆栈帧（%nopex 契约）");
        assertFalse(output.contains("java.lang.RuntimeException"), "控制台不得输出异常类名行（%nopex 契约）");
    }
}
