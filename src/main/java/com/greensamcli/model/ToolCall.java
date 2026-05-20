package com.greensamcli.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 表示 LLM 发起的一次工具调用请求。
 *
 * <p>当 LLM 认为需要调用工具时，assistant 消息中会包含 tool_calls 数组，
 * 每个 tool_call 结构如下：</p>
 *
 * <pre>{@code
 * {
 *   "id": "call_abc123",           // 本次调用的唯一标识，结果回传时需要匹配
 *   "type": "function",            // 固定为 "function"
 *   "function": {
 *     "name": "read_file",         // 要调用的工具名称
 *     "arguments": "{\"path\":\"/tmp/test.txt\"}"  // 参数，是 JSON 字符串而非对象！
 *   }
 * }
 * }</pre>
 *
 * <p><b>重要</b>：{@code function.arguments} 是一个 <b>JSON 字符串</b>，不是 JSON 对象。
 * 这意味着你需要先拿到字符串，再用 ObjectMapper 二次解析才能得到结构化数据。
 * 这是 OpenAI API 的设计，不是 bug。</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 确保即使 API 返回了我们
 * 没有定义的新字段，反序列化也不会报错。这在对接第三方 API 时是好习惯。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolCall {

    /** 本次工具调用的唯一标识，用于将工具执行结果与调用请求一一对应 */
    private String id;

    /** 调用类型，目前固定为 "function" */
    private String type;

    /** 被调用的函数信息，包含函数名和参数 */
    @JsonProperty("function")
    private FunctionCall function;

    /**
     * 被调用的函数的名称和参数。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FunctionCall {
        /** 工具名称，如 "read_file"、"list_files" */
        private String name;
        /**
         * 工具参数，格式为 JSON 字符串。
         * 例如：{@code "{\"path\":\"/tmp/test.txt\"}"}
         * 使用时需要 objectMapper.readTree(arguments) 解析为 JsonNode。
         */
        private String arguments;
    }
}
