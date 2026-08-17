package com.greensamcli.utils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Param;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ToolSchemaUtils} 单元测试——覆盖类型映射、命名、required 顺序、枚举与防呆异常。
 */
class ToolSchemaUtilsTest {

    /** 覆盖全部支持类型的样例参数 record */
    record SampleArgs(
            @Param(value = "文件路径", required = true) String path,
            @Param(value = "超时秒数", required = true) @JsonProperty("timeout_seconds") Integer timeoutSeconds,
            @Param("数量") Long count,
            @Param("开关") Boolean flag,
            @Param("比率") Double ratio,
            @Param("输出模式") Mode mode) {
    }

    /** 枚举参数：对外名通过 @JsonProperty 声明 */
    enum Mode {
        @JsonProperty("content")
        CONTENT,
        @JsonProperty("files_with_matches")
        FILES_WITH_MATCHES
    }

    /** 缺 @Param 注解的参数 record */
    record MissingParamArgs(
            @Param(value = "路径", required = true) String path,
            String comment) {
    }

    /** 使用不支持类型的参数 record */
    record UnsupportedTypeArgs(
            @Param("标签列表") List<String> tags) {
    }

    @Test
    void buildSchema_typeAndDescriptionPerComponent() {
        ObjectNode schema = ToolSchemaUtils.buildSchema(SampleArgs.class);

        assertEquals("object", schema.get("type").asText());
        assertEquals(6, schema.get("properties").size());
        assertEquals("string", schema.get("properties").get("path").get("type").asText());
        assertEquals("文件路径", schema.get("properties").get("path").get("description").asText());
        assertEquals("integer", schema.get("properties").get("timeout_seconds").get("type").asText());
        assertEquals("integer", schema.get("properties").get("count").get("type").asText());
        assertEquals("boolean", schema.get("properties").get("flag").get("type").asText());
        assertEquals("number", schema.get("properties").get("ratio").get("type").asText());
        assertEquals("string", schema.get("properties").get("mode").get("type").asText());
    }

    @Test
    void buildSchema_jsonPropertyOverridesComponentName() {
        ObjectNode schema = ToolSchemaUtils.buildSchema(SampleArgs.class);

        assertTrue(schema.get("properties").has("timeout_seconds"));
        assertFalse(schema.get("properties").has("timeoutSeconds"));
    }

    @Test
    void buildSchema_requiredKeepsDeclarationOrder() {
        List<String> required = new ArrayList<>();
        ToolSchemaUtils.buildSchema(SampleArgs.class).get("required")
                .forEach(node -> required.add(node.asText()));

        assertEquals(List.of("path", "timeout_seconds"), required);
    }

    @Test
    void buildSchema_enumEmitsWireValues() {
        List<String> enumValues = new ArrayList<>();
        ToolSchemaUtils.buildSchema(SampleArgs.class).get("properties").get("mode").get("enum")
                .forEach(node -> enumValues.add(node.asText()));

        assertEquals(List.of("content", "files_with_matches"), enumValues);
    }

    @Test
    void buildSchema_omitsRequiredKeyWhenAllOptional() {
        record OptionalArgs(@Param("路径") String path) {
        }

        ObjectNode schema = ToolSchemaUtils.buildSchema(OptionalArgs.class);

        assertFalse(schema.has("required"));
        assertTrue(schema.get("properties").has("path"));
    }

    @Test
    void buildSchema_missingParamAnnotationFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ToolSchemaUtils.buildSchema(MissingParamArgs.class));

        assertTrue(ex.getMessage().contains("comment"));
        assertTrue(ex.getMessage().contains("缺少 @Param"));
    }

    @Test
    void buildSchema_unsupportedTypeFailsFast() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ToolSchemaUtils.buildSchema(UnsupportedTypeArgs.class));

        assertTrue(ex.getMessage().contains("tags"));
        assertTrue(ex.getMessage().contains("不支持的类型"));
    }
}
