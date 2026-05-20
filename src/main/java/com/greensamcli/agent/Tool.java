package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具接口——所有可供 LLM 调用的工具都需要实现此接口。
 *
 * <p>一个工具由两部分组成：</p>
 * <ol>
 *   <li><b>定义</b>（getName / getDescription / getParameters）：告诉 LLM 这个工具能做什么、
 *       需要什么参数。这些信息会被序列化为 {@link com.greensamcli.model.ToolDefinition}，
 *       随每次 API 请求发送给 LLM。</li>
 *   <li><b>执行</b>（execute）：实际执行工具逻辑，返回文本结果给 LLM。</li>
 * </ol>
 *
 * <p>LLM 的决策过程：</p>
 * <pre>
 * LLM 收到工具定义 → 判断是否需要调用工具
 *     │                       │
 *     │ 是                    │ 否
 *     ▼                       ▼
 * 选择工具名 + 构造参数       直接返回文本回复
 *     │
 *     ▼
 * AgentLoop 通过 ToolRegistry 找到对应 Tool
 *     │
 *     ▼
 * 调用 tool.execute(arguments)
 *     │
 *     ▼
 * 结果回传给 LLM 继续推理
 * </pre>
 *
 * <p>添加新工具只需：① 实现此接口 ② 在 GreensamCli.main() 中注册。
 * 不需要修改任何其他代码。</p>
 *
 * <p>示例：</p>
 * <pre>{@code
 * public class WriteFileTool implements Tool {
 *     public String getName() { return "write_file"; }
 *     public String getDescription() { return "将内容写入文件"; }
 *     public JsonNode getParameters() { ... }  // JSON Schema
 *     public String execute(JsonNode args) {
 *         String path = args.get("path").asText();
 *         String content = args.get("content").asText();
 *         Files.writeString(Path.of(path), content);
 *         return "File written successfully: " + path;
 *     }
 * }
 * }</pre>
 */
public interface Tool {

    /**
     * 工具的唯一名称。LLM 在 tool_call 中通过此名称指定要调用的工具。
     * 建议使用 snake_case 命名，如 "read_file"、"list_files"。
     */
    String getName();

    /**
     * 工具的描述。LLM 根据此描述判断在什么场景下应该调用此工具。
     * 描述应该清晰说明工具的功能，例如 "读取本地文件系统的文件内容"。
     * 描述的质量直接影响 LLM 是否能正确选择工具。
     */
    String getDescription();

    /**
     * 工具参数的 JSON Schema 定义。
     * 告诉 LLM 这个工具接受什么参数、参数的类型和含义。
     * LLM 会据此构造合规的 arguments JSON。
     *
     * <p>示例 Schema：</p>
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "path": { "type": "string", "description": "文件路径" }
     *   },
     *   "required": ["path"]
     * }
     * }</pre>
     */
    JsonNode getParameters();

    /**
     * 执行工具逻辑。
     *
     * @param arguments LLM 构造的参数，已解析为 JsonNode，通过 args.get("字段名") 取值
     * @return 工具执行结果的文本描述，会作为 role="tool" 的消息回传给 LLM
     * @throws ToolExecutionException 工具执行失败时抛出，错误信息也会回传给 LLM
     */
    String execute(JsonNode arguments) throws ToolExecutionException;
}
