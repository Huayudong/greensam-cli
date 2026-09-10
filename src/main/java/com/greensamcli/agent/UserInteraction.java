package com.greensamcli.agent;

import java.util.List;

/**
 * 用户交互接口——审批确认与提问回答的抽象，供审批钩子（批次④）与
 * {@code AskUserTool} 等需要在回合执行中途与用户对话的组件使用。
 *
 * <p>回合执行在主线程上，实现方（CLI 层）直接复用行读取器读取输入即可；
 * 测试注入假实现即可覆盖全部交互逻辑，不依赖真实终端。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
public interface UserInteraction {

    /**
     * 审批确认：展示待执行的操作并等待用户三选。
     *
     * @param title  操作标题（如 "execute_command 命令执行审批"）
     * @param detail 操作详情（命令原文或完整 diff，多行文本）
     * @return 用户的决定
     */
    Decision confirmOperation(String title, String detail);

    /**
     * 向用户提问：展示问题与候选答案（a/b/c），支持用户自定义回答（d）。
     *
     * @param question 问题文本
     * @param options  候选答案，最多展示 3 个（a=推荐，b/c=其他方向）
     * @return 用户最终选定的答案文本（a/b/c 的原文或自定义回答）；
     *         null 表示用户未作答（如中断了提问）
     */
    String askQuestion(String question, List<String> options);

    /**
     * 审批决定。
     */
    enum Decision {
        /** 本次允许执行 */
        APPROVE,
        /** 拒绝本次执行（理由将作为 tool result 回传 LLM） */
        REJECT,
        /** 本会话内该工具不再询问 */
        APPROVE_ALWAYS
    }
}
