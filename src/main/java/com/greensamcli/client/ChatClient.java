package com.greensamcli.client;

import com.greensamcli.model.ChatMessage;
import com.greensamcli.model.ChatResponse;
import com.greensamcli.model.ToolDefinition;

import java.util.List;

/**
 * LLM API 的同步调用接口。
 *
 * <p>将对话历史和工具定义发送给 LLM，等待完整响应返回。
 * 这是非流式调用——整个响应生成完毕后才返回。</p>
 *
 * <p>与 {@link StreamingChatClient} 的区别：
 * ChatClient 等响应全部生成完才返回（适合后台处理），
 * StreamingChatClient 逐字返回（适合前端实时显示）。</p>
 */
public interface ChatClient {

    /**
     * 发送对话给 LLM 并获取响应。
     *
     * @param messages 完整的对话历史（包含 system、user、assistant、tool 消息）
     * @param tools    可用工具定义列表，LLM 据此决定是否调用工具；传 null 表示不带工具
     * @return LLM 的响应，可能包含文本内容或 tool_calls
     */
    ChatResponse send(List<ChatMessage> messages, List<ToolDefinition> tools);
}
