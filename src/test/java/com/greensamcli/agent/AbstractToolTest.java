package com.greensamcli.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbstractTool} 单元测试——覆盖参数绑定、默认值、必填校验与容错行为。
 */
class AbstractToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 用于测试的最小工具：一个必填参数 + 一个带默认值的可选参数 */
    static class EchoTool extends AbstractTool<EchoTool.Args> {

        EchoTool() {
            super(Args.class);
        }

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "测试用工具";
        }

        @Override
        protected String doExecute(Args args) {
            return args.path() + "|" + args.timeoutSeconds();
        }

        record Args(
                @Param(value = "文件路径", required = true) String path,
                @Param("超时秒数") @JsonProperty("timeout_seconds") Integer timeoutSeconds) {

            Args {
                if (timeoutSeconds == null) {
                    timeoutSeconds = 120;
                }
            }
        }
    }

    /** 两个必填参数的工具，用于验证一次性报出全部缺失项 */
    static class TwoRequiredTool extends AbstractTool<TwoRequiredTool.Args> {

        TwoRequiredTool() {
            super(Args.class);
        }

        @Override
        public String getName() {
            return "two_required";
        }

        @Override
        public String getDescription() {
            return "测试用工具";
        }

        @Override
        protected String doExecute(Args args) {
            return "ok";
        }

        record Args(
                @Param(value = "模式", required = true) String pattern,
                @Param(value = "路径", required = true) String path) {
        }
    }

    @Test
    void execute_bindsArgumentsAndAppliesDefault() throws Exception {
        EchoTool tool = new EchoTool();
        JsonNode args = mapper.readTree("{\"path\":\"/tmp/a.txt\"}");

        assertEquals("/tmp/a.txt|120", tool.execute(args));
    }

    @Test
    void execute_bindsSnakeCaseJsonProperty() throws Exception {
        EchoTool tool = new EchoTool();
        JsonNode args = mapper.readTree("{\"path\":\"/tmp/a.txt\",\"timeout_seconds\":30}");

        assertEquals("/tmp/a.txt|30", tool.execute(args));
    }

    @Test
    void getParameters_generatedFromRecord() {
        EchoTool tool = new EchoTool();
        JsonNode schema = tool.getParameters();

        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").has("path"));
        assertTrue(schema.get("properties").has("timeout_seconds"));
        assertEquals(1, schema.get("required").size());
        assertEquals("path", schema.get("required").get(0).asText());
    }

    @Test
    void execute_missingRequiredReportsAllMissing() throws Exception {
        TwoRequiredTool tool = new TwoRequiredTool();
        JsonNode args = mapper.readTree("{}");

        ToolExecutionException ex = assertThrows(ToolExecutionException.class, () -> tool.execute(args));

        assertTrue(ex.getMessage().contains("缺少必填参数"));
        assertTrue(ex.getMessage().contains("pattern"));
        assertTrue(ex.getMessage().contains("path"));
    }

    @Test
    void execute_nullArgumentsTreatedAsEmptyObject() {
        TwoRequiredTool tool = new TwoRequiredTool();

        ToolExecutionException ex = assertThrows(ToolExecutionException.class, () -> tool.execute(null));

        assertTrue(ex.getMessage().contains("缺少必填参数"));
    }

    @Test
    void execute_ignoresUnknownFields() throws Exception {
        EchoTool tool = new EchoTool();
        JsonNode args = mapper.readTree("{\"path\":\"/tmp/a.txt\",\"surprise\":true}");

        assertEquals("/tmp/a.txt|120", tool.execute(args));
    }

    @Test
    void execute_typeMismatchThrowsChineseError() throws Exception {
        EchoTool tool = new EchoTool();
        JsonNode args = mapper.readTree("{\"path\":\"/tmp/a.txt\",\"timeout_seconds\":\"abc\"}");

        ToolExecutionException ex = assertThrows(ToolExecutionException.class, () -> tool.execute(args));

        assertTrue(ex.getMessage().contains("参数解析失败"));
    }

    @Test
    void execute_compactConstructorDefaultSatisfiesRequired() throws Exception {
        // new_string 场景：required 但紧凑构造器兜底为空串，不应报缺失
        record TolerantArgs(
                @Param(value = "内容", required = true) String content) {

            TolerantArgs {
                if (content == null) {
                    content = "";
                }
            }
        }

        class TolerantTool extends AbstractTool<TolerantArgs> {
            TolerantTool() {
                super(TolerantArgs.class);
            }

            @Override
            public String getName() {
                return "tolerant";
            }

            @Override
            public String getDescription() {
                return "测试用工具";
            }

            @Override
            protected String doExecute(TolerantArgs args) {
                return "[" + args.content() + "]";
            }
        }

        assertEquals("[]", new TolerantTool().execute(mapper.readTree("{}")));
    }
}
