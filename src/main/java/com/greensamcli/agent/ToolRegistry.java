package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.greensamcli.model.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表——管理所有可供 LLM 调用的工具。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>注册工具：{@code register(new ReadFileTool())}</li>
 *   <li>根据名称查找工具：LLM 返回 tool_call 后，AgentLoop 通过名称找到对应 Tool 执行</li>
 *   <li>生成工具定义列表：每次 API 请求前，把所有注册的工具转为 ToolDefinition 格式发给 LLM</li>
 * </ol>
 *
 * <p>在 {@link com.greensamcli.GreensamCli} 中初始化并注册工具：</p>
 * <pre>{@code
 * ToolRegistry registry = new ToolRegistry();
 * registry.register(new ReadFileTool());
 * registry.register(new ListFilesTool());
 * }</pre>
 */
public class ToolRegistry {

    /**
     * 工具名 → 工具实例的映射
     */
    private final Map<String, Tool> tools = new HashMap<>();

    /**
     * 注册一个工具，后续 LLM 可以通过名称调用它
     */
    public void register(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * 根据名称获取工具实例
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 获取所有已注册工具的 API 定义列表。
     * 每次调用 LLM API 时，这个列表会作为 tools 参数发送，
     * 告诉 LLM "你可以调用这些工具"。
     */
    public List<ToolDefinition> getAllDefinitions() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Tool tool : tools.values()) {
            definitions.add(ToolDefinition.from(
                    tool.getName(),
                    tool.getDescription(),
                    tool.getParameters()
            ));
        }
        return definitions;
    }

    /**
     * 根据名称执行工具。
     *
     * @param name      工具名称（来自 LLM 的 tool_call.function.name）
     * @param arguments 工具参数（已解析为 JsonNode）
     * @return 工具执行的文本结果
     * @throws ToolExecutionException 工具不存在或执行失败时抛出
     */
    public String executeTool(String name, JsonNode arguments) throws ToolExecutionException {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new ToolExecutionException("未知工具: " + name);
        }
        return tool.execute(arguments);
    }
}
