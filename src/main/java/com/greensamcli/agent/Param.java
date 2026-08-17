package com.greensamcli.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具参数描述注解——标注在工具参数 record 的组件上，
 * 由 {@link com.greensamcli.utils.ToolSchemaUtils} 反射读取，生成参数的 JSON Schema。
 *
 * <p>一个 record 组件同时驱动两件事：</p>
 * <ol>
 *   <li><b>Schema 生成</b>：组件类型映射为 JSON 类型（String→string、Integer→integer 等），
 *       {@link #value()} 进入 description，{@link #required()} 进入 required 数组；</li>
 *   <li><b>参数绑定</b>：{@code AbstractTool} 用 Jackson 把 LLM 传来的 arguments
 *       反序列化为该 record，组件即强类型参数对象。</li>
 * </ol>
 *
 * <p>示例：</p>
 * <pre>{@code
 * public record Args(
 *         @Param(value = "Absolute or relative path to the file to read", required = true) String path,
 *         @Param("Timeout in seconds. Default 120.") @JsonProperty("timeout_seconds") Integer timeoutSeconds) {}
 * }</pre>
 *
 * <p>说明：description 面向 LLM，沿用项目既有英文文案；参数对外名（wire 名）与 Java
 * 组件名不一致时（如 snake_case 的 old_string），用 Jackson 的 {@code @JsonProperty}
 * 显式标注，Schema 生成与参数绑定共用同一个名字来源。</p>
 *
 * @author Macro Ray
 * @since 2026-08-14
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface Param {

    /**
     * 参数说明，原样进入 JSON Schema 的 description，供 LLM 阅读并决定如何传参。
     */
    String value();

    /**
     * 是否必填。true 的参数进入 Schema 的 required 数组，参数绑定时缺失会抛出中文报错。
     */
    boolean required() default false;
}
