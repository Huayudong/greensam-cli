package com.greensamcli.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.greensamcli.utils.ToolSchemaUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * 声明式参数工具基类——子类用一个 record 声明全部参数，
 * Schema 生成与参数绑定统一由基类完成，子类只实现 {@link #doExecute(Record)}。
 *
 * <p>子类写法：</p>
 * <pre>{@code
 * public class ReadFileTool extends AbstractTool<ReadFileTool.Args> {
 *     public ReadFileTool() { super(Args.class); }
 *
 *     public record Args(
 *             @Param(value = "Absolute or relative path to the file to read", required = true) String path) {}
 *
 *     @Override
 *     protected String doExecute(Args args) throws ToolExecutionException { ... args.path() ... }
 * }
 * }</pre>
 *
 * <p>参数绑定流程：</p>
 * <ol>
 *   <li>LLM 传来的 arguments 用 Jackson 反序列化为参数 record；
 *       反序列化会执行 record 的紧凑构造器，可选参数的默认值写在紧凑构造器里；</li>
 *   <li>再校验 {@code @Param(required=true)} 的组件是否为 null
 *       （紧凑构造器已填默认值的参数视为不缺失），一次性报出全部缺失项；</li>
 *   <li>绑定失败抛出中文 {@link ToolExecutionException}，由 AgentLoop 回传给 LLM。</li>
 * </ol>
 *
 * @author Macro Ray
 * @since 2026-08-14
 */
public abstract class AbstractTool<A extends Record> implements Tool {

    /**
     * 专用于参数绑定的 mapper：忽略 LLM 多传的字段（模型偶尔会自行发挥）
     */
    private static final ObjectMapper ARGUMENTS_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Class<A> argsType;

    /**
     * 构造时生成并缓存的参数 Schema。AgentLoop 每轮对话都会取全量工具定义，避免重复反射
     */
    private final JsonNode parameters;

    /**
     * @param argsType 参数 record 类型，构造时会校验其全部组件（类型映射、@Param 完整性）
     */
    protected AbstractTool(Class<A> argsType) {
        this.argsType = argsType;
        this.parameters = ToolSchemaUtils.buildSchema(argsType);
    }

    @Override
    public final JsonNode getParameters() {
        return parameters;
    }

    @Override
    public final String execute(JsonNode arguments) throws ToolExecutionException {
        return doExecute(bindArguments(arguments));
    }

    /**
     * 工具的具体执行逻辑，直接使用强类型参数对象。
     *
     * @param args 已绑定完成的参数 record，可选参数已填充默认值
     * @return 工具执行结果的文本描述，会作为 role="tool" 的消息回传给 LLM
     * @throws ToolExecutionException 工具执行失败时抛出，错误信息会回传给 LLM
     */
    protected abstract String doExecute(A args) throws ToolExecutionException;

    /**
     * 把 LLM 传来的 arguments 绑定为参数 record：反序列化（含紧凑构造器默认值）→ 必填项校验。
     */
    private A bindArguments(JsonNode arguments) throws ToolExecutionException {
        // null 或非对象节点（模型偶发行为）按空对象处理，让后续校验给出明确的缺失报错
        JsonNode source = arguments == null || !arguments.isObject()
                ? JsonNodeFactory.instance.objectNode()
                : arguments;

        A args;
        try {
            args = ARGUMENTS_MAPPER.treeToValue(source, argsType);
        } catch (JsonProcessingException e) {
            throw new ToolExecutionException("参数解析失败: " + e.getOriginalMessage(), e);
        }
        validateRequired(args);
        return args;
    }

    /**
     * 校验必填参数非 null。record 的紧凑构造器先于本方法执行，
     * 因此在紧凑构造器里补了默认值的必填参数不会被误报为缺失。
     */
    private void validateRequired(A args) throws ToolExecutionException {
        List<String> missing = new ArrayList<>();
        for (RecordComponent component : argsType.getRecordComponents()) {
            Param param = component.getAnnotation(Param.class);
            if (param == null || !param.required() || component.getType().isPrimitive()) {
                continue;
            }
            Object value;
            try {
                value = component.getAccessor().invoke(args);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new ToolExecutionException(
                        "参数校验失败: 无法读取参数 " + ToolSchemaUtils.propertyName(component), e);
            }
            if (value == null) {
                missing.add(ToolSchemaUtils.propertyName(component));
            }
        }
        if (!missing.isEmpty()) {
            throw new ToolExecutionException("缺少必填参数: " + String.join(", ", missing));
        }
    }
}
