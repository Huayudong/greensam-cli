package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ToolRegistry 钩子挂载与执行测试（批次②铺设的扩展点结构，
 * 批次④的交互式审批将以此接口落地，届时只加实现、不改注册表）。
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
class ToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造打桩工具：result 为 null 时执行即抛异常，否则返回 result 并置位 executed
     */
    private Tool stubTool(String name, String result, AtomicBoolean executed) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "stub"; }
            @Override public JsonNode getParameters() { return JsonNodeFactory.instance.objectNode(); }
            @Override public String execute(JsonNode args) {
                if (executed != null) {
                    executed.set(true);
                }
                if (result == null) {
                    throw new ToolExecutionException("boom");
                }
                return result;
            }
        };
    }

    @Test
    void hook_放行_工具执行且收到后置通知() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool("echo", "done", executed));

        List<String> events = new ArrayList<>();
        registry.addHook(new ToolExecutionHook() {
            @Override public String beforeExecute(String toolName, JsonNode arguments) {
                events.add("before:" + toolName);
                return null;
            }

            @Override public void afterExecute(String toolName, JsonNode arguments,
                                               String result, Exception error) {
                events.add("after:" + toolName + ":" + result + ":" + error);
            }
        });

        assertEquals("done", registry.executeTool("echo", JsonNodeFactory.instance.objectNode()));
        assertTrue(executed.get());
        assertEquals(List.of("before:echo", "after:echo:done:null"), events);
    }

    @Test
    void hook_拦截_工具不执行_拒绝理由作异常抛出() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool("echo", "done", executed));
        registry.addHook((toolName, arguments) -> "用户拒绝了该操作");

        ToolExecutionException ex = assertThrows(ToolExecutionException.class,
                () -> registry.executeTool("echo", JsonNodeFactory.instance.objectNode()));

        assertEquals("用户拒绝了该操作", ex.getMessage());
        assertFalse(executed.get(), "被拦截的工具不应执行");
    }

    @Test
    void hook_工具失败_后置通知收到异常() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool("bad", null, executed));

        List<Exception> errors = new ArrayList<>();
        registry.addHook(new ToolExecutionHook() {
            @Override public String beforeExecute(String toolName, JsonNode arguments) {
                return null;
            }

            @Override public void afterExecute(String toolName, JsonNode arguments,
                                               String result, Exception error) {
                errors.add(error);
            }
        });

        assertThrows(ToolExecutionException.class,
                () -> registry.executeTool("bad", JsonNodeFactory.instance.objectNode()));

        assertEquals(1, errors.size());
        assertInstanceOf(ToolExecutionException.class, errors.get(0));
        assertTrue(executed.get());
    }

    @Test
    void hook_未挂载时行为与从前一致() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(stubTool("echo", "done", null));

        assertEquals("done", registry.executeTool("echo", JsonNodeFactory.instance.objectNode()));
        assertEquals("未知工具: nope", assertThrows(ToolExecutionException.class,
                () -> registry.executeTool("nope", JsonNodeFactory.instance.objectNode())).getMessage());
    }
}
