package com.greensamcli.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送给 LLM API 的工具定义，告诉 LLM "你能调用哪些工具"。
 *
 * <p>每次 API 请求都会在请求体中携带 tools 数组，格式如下：</p>
 *
 * <pre>{@code
 * "tools": [{
 *   "type": "function",
 *   "function": {
 *     "name": "read_file",
 *     "description": "读取文件内容",
 *     "parameters": {
 *       "type": "object",
 *       "properties": {
 *         "path": { "type": "string", "description": "文件路径" }
 *       },
 *       "required": ["path"]
 *     }
 *   }
 * }]
 * }</pre>
 *
 * <p>LLM 根据这些定义来决定：</p>
 * <ol>
 *   <li>是否需要调用工具（如果不调用，直接返回文本回复）</li>
 *   <li>调用哪个工具（根据 name 匹配）</li>
 *   <li>传什么参数（根据 parameters 的 JSON Schema 约束）</li>
 * </ol>
 *
 * <p>设计上与 {@link com.greensamcli.agent.Tool} 接口分离：
 * Tool 是可执行的业务接口，ToolDefinition 是 API 的 JSON 线格式。
 * 通过 {@link #from(String, String, JsonNode)} 工厂方法从 Tool 自动生成。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    /** 固定为 "function" */
    private String type;

    /** 函数定义，包含名称、描述和参数 Schema */
    private FunctionDef function;

    /**
     * 函数的具体定义。
     * LLM 根据 description 决定何时调用，根据 parameters 约束参数格式。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionDef {
        /** 工具名称，LLM 在 tool_call 中通过此名称指定要调用的工具 */
        private String name;
        /** 工具描述，LLM 根据此描述判断在什么场景下应该调用此工具 */
        private String description;
        /** 参数的 JSON Schema，定义工具接受的参数类型和结构 */
        private JsonNode parameters;
    }

    /**
     * 从工具信息快速创建 ToolDefinition。
     * 通常在 AgentLoop 发送请求前，由 ToolRegistry 遍历所有注册的 Tool 自动生成。
     */
    public static ToolDefinition from(String name, String description, JsonNode parameters) {
        return ToolDefinition.builder()
                .type("function")
                .function(new FunctionDef(name, description, parameters))
                .build();
    }
}
