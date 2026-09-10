package com.greensamcli.tools;

import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
import com.greensamcli.agent.ToolExecutionException;
import com.greensamcli.agent.UserInteraction;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * "提问用户"工具——Agent 面对多种合理方案或需要用户决策时，
 * 主动向用户展示问题与候选答案，等待用户选择后带着答案继续推理。
 *
 * <p>交互形态（由 {@link UserInteraction#askQuestion} 实现）：</p>
 * <pre>
 * ❓ 应该用哪种方式实现重试？
 *   [a] Spring Retry 注解方式（推荐）
 *   [b] 手写 while 循环 + 退避
 *   [c] 引入 resilience4j
 *   [d] 自定义回答
 * ? 请选择 &gt;
 * </pre>
 *
 * <p>纯交互、无副作用，属于读类工具，不需要审批。用户的选择会作为
 * tool result 回传 LLM，让后续推理尊重用户的决定。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
@Slf4j
public class AskUserTool extends AbstractTool<AskUserTool.Args> {

    /**
     * 候选答案上限：对应终端上的 a/b/c 三个选项（d 固定为自定义回答）
     */
    private static final int MAX_OPTIONS = 3;

    private final UserInteraction interaction;

    public AskUserTool(UserInteraction interaction) {
        super(Args.class);
        this.interaction = interaction;
    }

    @Override
    public String getName() {
        return "ask_user";
    }

    @Override
    public String getDescription() {
        return "ask_user 工具：向用户提问并等待选择。当存在多种合理方案、需要用户决策"
                + "或确认方向时使用，不要用于查资料类问题。提供 3 个候选答案"
                + "（options 为字符串数组，每个元素就是选项文字本身）："
                + "a 为最契合当前项目、最推荐的方案，b 和 c 为其他方向的合理方案；"
                + "用户也可以输入自定义回答（d）。用户的最终选择会返回给你，请严格按用户的选择继续。";
    }

    /**
     * 参数声明：question 必填，options 为 3 个候选答案。
     */
    public record Args(
            @Param(value = "要问用户的问题，一句话，具体明确", required = true) String question,
            @Param(value = "候选答案的字符串数组，恰好 3 个，每个元素就是选项文字本身，"
                    + "如 [\"设计徽章的具体规则\",\"梳理用户旅程\",\"技术架构选型\"]，不要传 {key,value} 对象；"
                    + "第 1 个是最契合项目、最推荐的方案，后 2 个为其他方向的方案",
                    required = true) List<String> options) {
    }

    /**
     * 展示问题与候选答案，等待用户选择。
     *
     * @param args 已绑定的参数
     * @return 用户选择的答案文本，会作为 role="tool" 的消息回传给 LLM
     * @throws ToolExecutionException 问题为空或候选答案为空时抛出
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        if (args.question() == null || args.question().isBlank()) {
            throw new ToolExecutionException("question 不能为空");
        }
        List<String> options = args.options();
        if (options == null || options.isEmpty()) {
            throw new ToolExecutionException("options 不能为空，请提供 3 个候选答案");
        }
        List<String> shown = options.size() > MAX_OPTIONS ? options.subList(0, MAX_OPTIONS) : options;
        log.info("向用户提问: question={}, options={}", args.question(), shown);

        String answer = interaction.askQuestion(args.question(), shown);
        if (answer == null || answer.isBlank()) {
            log.info("用户未回答提问");
            return "用户本次未作答。请自行选择最稳妥的方案继续，并说明你的选择理由。";
        }
        log.info("用户已回答提问: answer={}", answer);
        return "用户的选择：" + answer + "。请严格按用户的选择继续执行。";
    }
}
