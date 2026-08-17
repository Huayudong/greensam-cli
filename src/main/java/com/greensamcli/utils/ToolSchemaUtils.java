package com.greensamcli.utils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Param;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具参数 Schema 生成工具——从 {@link Param} 注解标注的参数 record 反射生成
 * LLM 工具调用所需的 JSON Schema。
 *
 * <p>与 {@link com.greensamcli.agent.AbstractTool} 配套使用：record 组件的声明顺序
 * 即 Schema 中 properties / required 的顺序；组件类型按固定映射表转为 JSON 类型。
 * record 同时也是参数绑定的目标类型（Jackson 反序列化），保证"模型看到的表单"
 * 与"代码里的解析"永远出自同一份声明。</p>
 *
 * @author Macro Ray
 * @since 2026-08-14
 */
public final class ToolSchemaUtils {

    private ToolSchemaUtils() {
    }

    /**
     * 根据参数 record 生成工具参数的 JSON Schema。
     *
     * <p>生成规则：</p>
     * <ul>
     *   <li>顶层固定为 {@code {"type":"object","properties":{...},"required":[...]}}；</li>
     *   <li>每个 record 组件必须标注 {@link Param}（缺注解直接抛异常，保证参数都有描述）；</li>
     *   <li>参数对外名取访问器上的 {@code @JsonProperty}，无注解时用组件名；</li>
     *   <li>required 按声明顺序收集 {@code @Param(required=true)} 的参数，为空时不输出该键。</li>
     * </ul>
     *
     * @param argsType 参数 record 类型
     * @return JSON Schema 根节点
     * @throws IllegalArgumentException 组件缺 {@link Param} 注解，或组件类型不在支持范围内
     */
    public static ObjectNode buildSchema(Class<? extends Record> argsType) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ArrayNode required = JsonNodeFactory.instance.arrayNode();

        for (RecordComponent component : argsType.getRecordComponents()) {
            Param param = component.getAnnotation(Param.class);
            if (param == null) {
                throw new IllegalArgumentException(
                        "工具参数 record " + argsType.getSimpleName() + " 的组件 " + component.getName()
                                + " 缺少 @Param 注解，每个参数都必须声明描述");
            }

            properties.set(propertyName(component), buildProperty(component, param, argsType));
            if (param.required()) {
                required.add(propertyName(component));
            }
        }

        schema.set("properties", properties);
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    /**
     * 返回参数的对外名（wire 名）：优先取访问器上的 {@code @JsonProperty}，
     * 无注解时使用组件名。参数绑定时 Jackson 读取的是同一份注解，两边名字必然一致。
     *
     * @param component record 组件
     * @return 参数对外名
     */
    public static String propertyName(RecordComponent component) {
        JsonProperty jsonProperty = component.getAccessor().getAnnotation(JsonProperty.class);
        return jsonProperty != null ? jsonProperty.value() : component.getName();
    }

    /**
     * 生成单个参数的 Schema 节点（type / description / enum）。
     */
    private static ObjectNode buildProperty(RecordComponent component, Param param, Class<?> argsType) {
        ObjectNode property = JsonNodeFactory.instance.objectNode();
        property.put("type", jsonType(component, argsType));
        property.put("description", param.value());

        // 枚举参数额外输出合法取值列表，降低模型传错值的概率
        if (component.getType().isEnum()) {
            ArrayNode enumValues = property.putArray("enum");
            for (Object constant : component.getType().getEnumConstants()) {
                enumValues.add(enumWireName((Enum<?>) constant));
            }
        }
        return property;
    }

    /**
     * 组件类型映射为 JSON Schema 类型，不认识的类型在工具构造期即抛异常（fail fast）。
     */
    private static String jsonType(RecordComponent component, Class<?> argsType) {
        Class<?> type = component.getType();
        if (type == String.class) {
            return "string";
        }
        if (type == Integer.class || type == Integer.TYPE
                || type == Long.class || type == Long.TYPE) {
            return "integer";
        }
        if (type == Boolean.class || type == Boolean.TYPE) {
            return "boolean";
        }
        if (type == Double.class || type == Double.TYPE) {
            return "number";
        }
        if (type.isEnum()) {
            return "string";
        }
        throw new IllegalArgumentException(
                "工具参数 record " + argsType.getSimpleName() + " 的组件 " + component.getName()
                        + " 使用了不支持的类型: " + type.getSimpleName()
                        + "，支持 String / Integer / Long / Boolean / Double / 枚举");
    }

    /**
     * 返回枚举常量的对外名：常量上标注 {@code @JsonProperty} 时用注解值，否则用常量名。
     */
    private static String enumWireName(Enum<?> constant) {
        JsonProperty jsonProperty;
        try {
            jsonProperty = constant.getClass().getField(constant.name()).getAnnotation(JsonProperty.class);
        } catch (NoSuchFieldException e) {
            // getEnumConstants 返回的常量名必然存在于字段中，此分支仅作防御
            return constant.name();
        }
        return jsonProperty != null ? jsonProperty.value() : constant.name();
    }
}
