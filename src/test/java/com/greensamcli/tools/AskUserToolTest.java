package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.ToolExecutionException;
import com.greensamcli.agent.UserInteraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AskUserTool 提问工具测试（批次④）：a/b/c 选项原文返回、d 自定义回答、
 * 未作答降级、参数校验。
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
class AskUserToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private String scriptedAnswer;
    private List<String> askedOptions;
    private String askedQuestion;

    @BeforeEach
    void setUp() {
        scriptedAnswer = null;
        askedOptions = null;
        askedQuestion = null;
    }

    private AskUserTool toolWithScriptedAnswer() {
        return new AskUserTool(new UserInteraction() {
            @Override
            public Decision confirmOperation(String title, String detail) {
                throw new AssertionError("提问工具不应调用 confirmOperation");
            }

            @Override
            public String askQuestion(String question, List<String> options) {
                askedQuestion = question;
                askedOptions = new ArrayList<>(options);
                return scriptedAnswer;
            }
        });
    }

    private JsonNode args(String question, String... options) {
        ObjectNode node = mapper.createObjectNode().put("question", question);
        ArrayNode arr = node.putArray("options");
        for (String option : options) {
            arr.add(option);
        }
        return node;
    }

    @Test
    void 选择a_返回推荐选项原文() {
        scriptedAnswer = "Spring Retry 注解方式";
        String result = toolWithScriptedAnswer().execute(
                args("用哪种重试方案？", "Spring Retry 注解方式", "手写循环", "resilience4j"));

        assertEquals("用户的选择：Spring Retry 注解方式。请严格按用户的选择继续执行。", result);
        assertEquals(3, askedOptions.size());
        assertEquals("Spring Retry 注解方式", askedOptions.get(0));
    }

    @Test
    void 选择d_返回自定义回答() {
        scriptedAnswer = "都用，先试 a 不行再换 b";
        String result = toolWithScriptedAnswer().execute(
                args("用哪种方案？", "方案A", "方案B", "方案C"));
        assertTrue(result.contains("都用，先试 a 不行再换 b"));
    }

    @Test
    void 未作答_降级为让LLM自行决策() {
        scriptedAnswer = null;
        String result = toolWithScriptedAnswer().execute(
                args("用哪种方案？", "方案A", "方案B", "方案C"));
        assertTrue(result.contains("用户本次未作答"));
        assertTrue(result.contains("自行选择"));
    }

    @Test
    void 超过三个候选_只展示前三个() {
        scriptedAnswer = "方案A";
        toolWithScriptedAnswer().execute(
                args("用哪种方案？", "方案A", "方案B", "方案C", "方案D"));
        assertEquals(3, askedOptions.size());
        assertNull(askedOptions.stream().filter("方案D"::equals).findFirst().orElse(null));
    }

    @Test
    void 问题为空_报错() {
        ToolExecutionException e = assertThrows(ToolExecutionException.class,
                () -> toolWithScriptedAnswer().execute(args("  ", "A", "B", "C")));
        assertTrue(e.getMessage().contains("question"));
    }

    @Test
    void 候选为空_报错() {
        ToolExecutionException e = assertThrows(ToolExecutionException.class,
                () -> toolWithScriptedAnswer().execute(args("问题")));
        assertTrue(e.getMessage().contains("options"));
    }

    @Test
    void 选项传对象元素_宽容解析出文本() {
        // 复现线上事故形态：模型无视 items:string 约束，把选项包成 {key,value} 对象
        ArrayNode options = mapper.createArrayNode();
        options.addObject().put("key", "a").put("value", "方案A");
        options.addObject().put("key", "b").put("value", "方案B");
        options.addObject().put("key", "c").put("value", "方案C");
        scriptedAnswer = "方案A";

        String result = toolWithScriptedAnswer().execute(objectElementArgs(options));

        assertEquals(List.of("方案A", "方案B", "方案C"), askedOptions);
        assertEquals("用户的选择：方案A。请严格按用户的选择继续执行。", result);
    }

    @Test
    void 对象元素_按字段优先级提取文本() {
        ArrayNode options = mapper.createArrayNode();
        options.addObject().put("key", "a").put("label", "方案A");
        options.addObject().put("text", "方案B");
        options.add("方案C");
        scriptedAnswer = "方案B";

        toolWithScriptedAnswer().execute(objectElementArgs(options));

        // value/text/label 优先于 key：只带 key 时才退回取 key
        assertEquals(List.of("方案A", "方案B", "方案C"), askedOptions);
    }

    @Test
    void 非字符串元素_按字面值转文本() {
        ArrayNode options = mapper.createArrayNode();
        options.add(1);
        options.add(2);
        options.add(3);
        scriptedAnswer = "2";

        toolWithScriptedAnswer().execute(objectElementArgs(options));

        assertEquals(List.of("1", "2", "3"), askedOptions);
    }

    /**
     * 构造 options 为任意 JsonNode 数组的请求参数（用于宽容解析用例）。
     */
    private JsonNode objectElementArgs(ArrayNode options) {
        ObjectNode node = mapper.createObjectNode().put("question", "用哪种方案？");
        node.set("options", options);
        return node;
    }

    @Test
    void 工具名与描述_符合wire契约() {
        AskUserTool tool = toolWithScriptedAnswer();
        assertEquals("ask_user", tool.getName());
        assertTrue(tool.getDescription().contains("ask_user"));
        // Schema：options 应为 string 数组
        JsonNode schema = tool.getParameters();
        assertEquals("array", schema.get("properties").get("options").get("type").asText());
        assertEquals("string", schema.get("properties").get("options").get("items").get("type").asText());
    }
}
