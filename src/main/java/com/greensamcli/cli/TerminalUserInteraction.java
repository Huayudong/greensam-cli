package com.greensamcli.cli;

import com.greensamcli.agent.UserInteraction;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.util.List;

/**
 * 基于真实终端的用户交互实现——审批确认与提问回答。
 *
 * <p>回合执行在主线程上，执行到审批/提问点时主线程空闲，直接复用 REPL 的
 * {@link LineReader} 读取输入即可（此时它不在 readLine 中，重入安全）。
 * 由 {@code GreensamCli} 创建并注入审批钩子与 AskUserTool，
 * {@code Repl} 在终端就绪后调用 {@link #bind} 绑定行读取器与渲染器。</p>
 *
 * <p><b>中断语义</b>：审批/提问期间按 Ctrl+C / Ctrl+D 视为「拒绝 / 未作答」，
 * 不会中断整个回合（此时终端处于 raw 模式，JLine 以按键形式接管 Ctrl+C，
 * 不产生中断信号，与回合执行中的「中断当前回合」互不干扰）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
@Slf4j
public class TerminalUserInteraction implements UserInteraction {

    private LineReader lineReader;
    private CliRenderer renderer;

    /**
     * 绑定行读取器与渲染器（Repl 终端就绪后调用一次）
     */
    public void bind(LineReader lineReader, CliRenderer renderer) {
        this.lineReader = lineReader;
        this.renderer = renderer;
    }

    @Override
    public Decision confirmOperation(String title, String detail) {
        renderer.displayApproval(title, detail);
        while (true) {
            String input = readLine("? 请选择 [y] 本次允许 / [n] 拒绝 / [a] 本会话内不再询问 > ");
            if (input == null) {
                log.info("审批被用户中断（Ctrl+C/Ctrl+D），按拒绝处理: title={}", title);
                return Decision.REJECT;
            }
            switch (input.trim().toLowerCase()) {
                case "y", "yes":
                    return Decision.APPROVE;
                case "a", "always":
                    return Decision.APPROVE_ALWAYS;
                case "n", "no":
                    return Decision.REJECT;
                default:
                    renderer.displaySystem("无效输入，请输入 y / n / a");
            }
        }
    }

    @Override
    public String askQuestion(String question, List<String> options) {
        renderer.displayQuestion(question, options);
        while (true) {
            String input = readLine("? 请选择 a/b/c/d > ");
            if (input == null) {
                log.info("提问被用户中断（Ctrl+C/Ctrl+D），按未作答处理");
                return null;
            }
            String choice = input.trim().toLowerCase();
            // a/b/c：返回对应候选原文；索引越界视为无效输入
            if (choice.length() == 1 && choice.charAt(0) >= 'a'
                    && choice.charAt(0) < 'a' + options.size()) {
                int index = choice.charAt(0) - 'a';
                return options.get(index);
            }
            if ("d".equals(choice)) {
                String custom = readLine("> 请输入你的回答： ");
                return custom == null || custom.isBlank() ? null : custom.trim();
            }
            renderer.displaySystem("无效输入，请输入 a / b / c / d");
        }
    }

    /**
     * 读取一行用户输入；Ctrl+C（UserInterruptException）与 Ctrl+D（EndOfFileException）
     * 返回 null，由调用方决定语义（审批=拒绝，提问=未作答）。
     */
    private String readLine(String prompt) {
        if (lineReader == null) {
            log.warn("用户交互尚未绑定行读取器，按中断处理");
            return null;
        }
        try {
            return lineReader.readLine(prompt);
        } catch (UserInterruptException e) {
            return null;
        } catch (EndOfFileException e) {
            return null;
        }
    }
}
