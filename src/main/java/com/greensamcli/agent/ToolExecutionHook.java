package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具执行钩子——围绕 {@link ToolRegistry#executeTool} 的横切扩展点。
 *
 * <p>「该不该执行这个工具」这类与具体工具无关的横切关注点（典型如
 * 交互式审批，见路线图批次④）通过此接口挂载：注册表在每次工具执行前
 * 依次回调全部钩子的 {@link #beforeExecute}，任一返回非 null 即拦截；
 * 执行结束后回调 {@link #afterExecute}。AgentLoop 与各工具实现对此保持无知。</p>
 *
 * <p>本批（批次②）仅铺设结构，不注册任何具体钩子；批次④的审批拦截
 * 将以此接口落地，无需再改动核心类。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
public interface ToolExecutionHook {

    /**
     * 工具执行前调用，按注册顺序依次回调。
     *
     * @param toolName  工具名称（来自 LLM 的 tool_call.function.name）
     * @param arguments 工具参数（已解析的 JSON 对象节点）
     * @return null 表示放行；非 null 为拦截理由，本次执行被跳过，
     *         理由文本将作为工具结果回传给 LLM
     */
    String beforeExecute(String toolName, JsonNode arguments);

    /**
     * 工具执行完成后调用（成功或失败均触发；被拦截未执行时不触发）。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @param result    执行结果文本；执行失败时为 null
     * @param error     执行失败时抛出的异常；成功时为 null
     */
    default void afterExecute(String toolName, JsonNode arguments, String result, Exception error) {
    }
}
