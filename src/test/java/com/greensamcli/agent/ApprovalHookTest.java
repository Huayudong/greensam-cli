package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.UserInteraction.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApprovalHook 审批拦截测试（批次④）。
 *
 * <p>全部注入假 {@link UserInteraction}，覆盖：读工具放行、写工具触发交互、
 * 三种决定的语义、[a] 会话内放行、autoApprove 全放行、diff 详情组装。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
class ApprovalHookTest {

    @TempDir
    Path tempDir;

    private ObjectMapper mapper;
    /** 假交互返回的决定；null 表示期望不被调用 */
    private UserInteraction.Decision scriptedDecision;
    private int confirmCalls;
    private List<String> confirmDetails;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        confirmCalls = 0;
        confirmDetails = new ArrayList<>();
        scriptedDecision = null;
    }

    /**
     * 记录调用并返回脚本化决定的假交互
     */
    private UserInteraction scriptedInteraction() {
        return new UserInteraction() {
            @Override
            public Decision confirmOperation(String title, String detail) {
                confirmCalls++;
                confirmDetails.add(title + "\n" + detail);
                if (scriptedDecision == null) {
                    throw new AssertionError("不应触发审批交互");
                }
                return scriptedDecision;
            }

            @Override
            public String askQuestion(String question, List<String> options) {
                throw new AssertionError("审批钩子不应调用 askQuestion");
            }
        };
    }

    private JsonNode args(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 读类工具_直接放行不交互() {
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);
        for (String tool : new String[]{"read_file", "list_files", "grep", "glob", "ask_user"}) {
            assertNull(hook.beforeExecute(tool, JsonNodeFactory.instance.objectNode()));
        }
        assertEquals(0, confirmCalls);
    }

    @Test
    void 命令工具_批准时放行() {
        scriptedDecision = Decision.APPROVE;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);

        assertNull(hook.beforeExecute("execute_command", args("{\"command\":\"ping -n 2 127.0.0.1\"}")));
        assertEquals(1, confirmCalls);
        // 详情应包含完整命令原文
        assertTrue(confirmDetails.get(0).contains("ping -n 2 127.0.0.1"), confirmDetails.get(0));
    }

    @Test
    void 命令工具_拒绝时返回拒绝理由() {
        scriptedDecision = Decision.REJECT;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);

        String veto = hook.beforeExecute("execute_command", args("{\"command\":\"format C:\"}"));
        assertNotNull(veto);
        assertTrue(veto.contains("用户拒绝了该操作"), veto);
        assertTrue(veto.contains("execute_command"), veto);
    }

    @Test
    void 本会话放行_同工具后续不再询问() {
        scriptedDecision = Decision.APPROVE_ALWAYS;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);

        assertNull(hook.beforeExecute("execute_command", args("{\"command\":\"echo 1\"}")));
        assertNull(hook.beforeExecute("execute_command", args("{\"command\":\"echo 2\"}")));
        assertEquals(1, confirmCalls, "第二次同工具不应再询问");
    }

    @Test
    void 会话放行_按工具隔离() {
        scriptedDecision = Decision.APPROVE_ALWAYS;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);

        assertNull(hook.beforeExecute("write_file", writeArgs(tempDir, "a.txt", "1")));
        // write_file 放行了，execute_command 仍要询问
        assertNull(hook.beforeExecute("execute_command", args("{\"command\":\"echo 1\"}")));
        assertEquals(2, confirmCalls);
    }

    @Test
    void 自动放行_不交互直接通过() {
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), true);

        assertNull(hook.beforeExecute("execute_command", args("{\"command\":\"format C:\"}")));
        assertNull(hook.beforeExecute("write_file", writeArgs(tempDir, "x.txt", "x")));
        assertEquals(0, confirmCalls);
    }

    @Test
    void 写文件审批_详情包含完整diff() throws Exception {
        Path target = tempDir.resolve("demo.txt");
        Files.writeString(target, "line1\nline2\nline3\n");
        scriptedDecision = Decision.APPROVE;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);

        JsonNode writeArgs = args("{\"path\":\"" + target.toString().replace("\\", "\\\\")
                + "\",\"content\":\"line1\\nline2 changed\\nline3\\nline4\\n\"}");
        assertNull(hook.beforeExecute("write_file", writeArgs));

        String detail = confirmDetails.get(0);
        assertTrue(detail.contains("- line2"), "diff 应包含删除行: " + detail);
        assertTrue(detail.contains("+ line2 changed"), "diff 应包含新增行: " + detail);
        assertTrue(detail.contains("+ line4"), "diff 应包含追加行: " + detail);
    }

    @Test
    void 写文件审批_新文件提示新建() {
        scriptedDecision = Decision.APPROVE;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);
        Path target = tempDir.resolve("not-exist.txt");

        assertNull(hook.beforeExecute("write_file",
                writeArgs(tempDir, "not-exist.txt", "hello")));
        String detail = confirmDetails.get(0);
        assertTrue(detail.contains("新建文件"), detail);
        assertTrue(detail.contains("hello"), detail);
        assertFalse(Files.exists(target), "审批展示不应真正写文件");
    }

    @Test
    void 编辑文件审批_详情包含old到new的diff() {
        scriptedDecision = Decision.APPROVE;
        ApprovalHook hook = new ApprovalHook(scriptedInteraction(), false);

        JsonNode editArgs = args("{\"path\":\"a.txt\",\"old_string\":\"foo\",\"new_string\":\"bar\"}");
        assertNull(hook.beforeExecute("edit_file", editArgs));

        String detail = confirmDetails.get(0);
        assertTrue(detail.contains("- foo"), detail);
        assertTrue(detail.contains("+ bar"), detail);
    }

    private JsonNode writeArgs(Path dir, String fileName, String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("path", dir.resolve(fileName).toString());
        node.put("content", content);
        return node;
    }
}
