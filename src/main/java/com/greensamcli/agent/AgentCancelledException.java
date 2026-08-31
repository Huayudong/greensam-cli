package com.greensamcli.agent;

/**
 * 回合被用户取消时抛出的控制流异常。
 *
 * <p>由 {@link AgentLoop#cancel()} 置位取消标志，AgentLoop 在各安全点
 * （发送 LLM 前后、执行每个工具前）检查并抛出本异常；调用方（Repl）
 * 捕获后提示「⏹ 已中断当前回合」并回到提示符，不应视作错误。</p>
 *
 * <p>抛出前对话历史已保证结构合法（本轮发起的 tool_call 均有对应结果），
 * 用户中断后可直接继续提问（如「刚才做到哪了」）。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
public class AgentCancelledException extends RuntimeException {

    public AgentCancelledException() {
        super("用户已中断当前回合");
    }
}
