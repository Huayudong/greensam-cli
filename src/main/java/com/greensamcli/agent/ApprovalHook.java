package com.greensamcli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.greensamcli.agent.UserInteraction.Decision;
import com.greensamcli.utils.LineDiffUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 交互式审批钩子（批次④）——挂载于 {@link ToolRegistry#executeTool} 前后，
 * 为写副作用工具提供「执行前三选审批」。
 *
 * <p><b>范围</b>：{@code write_file} / {@code edit_file} / {@code execute_command}
 * 需审批；读类工具自动放行。</p>
 *
 * <p><b>交互</b>：命令工具展示完整命令原文，写文件工具展示完整 diff；
 * 用户三选 {@code [y]} 本次允许 / {@code [n]} 拒绝 / {@code [a]} 本会话内该工具
 * 不再询问。拒绝不是异常中断，而是返回拒绝理由作为 tool result 回传 LLM
 * （由 ToolRegistry 包装），让它有机会换方案。</p>
 *
 * <p><b>跳过</b>：{@code autoApprove=true}（环境变量 {@code GREENSAM_AUTO_APPROVE=true}
 * 或启动参数 {@code --yolo}）时钩子直接放行，不与用户交互。</p>
 *
 * @author Macro Ray
 * @since 2026-08-31
 */
@Slf4j
public class ApprovalHook implements ToolExecutionHook {

    /**
     * 需要审批的写副作用工具名
     */
    static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "execute_command");

    /**
     * diff 展示的最大字符数，防止超大文件撑爆审批界面
     */
    private static final int MAX_DETAIL_CHARS = 4000;

    private final UserInteraction interaction;
    private final boolean autoApprove;
    /**
     * [a] 选过的工具：本会话内不再询问（工具粒度）
     */
    private final Set<String> sessionApproved = new java.util.HashSet<>();

    public ApprovalHook(UserInteraction interaction, boolean autoApprove) {
        this.interaction = interaction;
        this.autoApprove = autoApprove;
    }

    @Override
    public String beforeExecute(String toolName, JsonNode arguments) {
        if (autoApprove || !WRITE_TOOLS.contains(toolName) || sessionApproved.contains(toolName)) {
            return null;
        }

        String detail = buildDetail(toolName, arguments);
        Decision decision = interaction.confirmOperation(toolName + " 操作审批", detail);

        if (decision == Decision.APPROVE_ALWAYS) {
            log.info("用户选择本会话放行该工具: tool={}", toolName);
            sessionApproved.add(toolName);
            return null;
        }
        if (decision == Decision.APPROVE) {
            log.info("用户批准本次操作: tool={}", toolName);
            return null;
        }
        log.info("用户拒绝了本次操作，理由将回传 LLM: tool={}", toolName);
        // 拒绝理由作为 tool result 回传 LLM，让它有机会换方案（审批是反馈信号，不是刹车）
        return "用户拒绝了该操作（" + toolName + "）。请尊重用户的决定，"
                + "换一种不需要该操作的方案，或先询问用户的意图后再继续。";
    }

    /**
     * 组装审批详情：命令工具给命令原文，写文件工具给完整 diff。
     */
    private String buildDetail(String toolName, JsonNode arguments) {
        return switch (toolName) {
            case "execute_command" -> commandDetail(arguments);
            case "write_file" -> {
                String path = arguments.path("path").asText("");
                String content = arguments.path("content").asText("");
                yield path + "\n" + diffWithExistingFile(path, content);
            }
            case "edit_file" -> {
                String path = arguments.path("path").asText("");
                String oldString = arguments.path("old_string").asText("");
                String newString = arguments.path("new_string").asText("");
                String replaceAll = arguments.path("replace_all").asText("false");
                yield path + "（replace_all=" + replaceAll + "）\n"
                        + LineDiffUtils.unifiedDiff(oldString, newString);
            }
            default -> arguments.toString();
        };
    }

    /**
     * 命令审批详情：完整命令原文 + 工作目录。
     */
    private String commandDetail(JsonNode arguments) {
        StringBuilder sb = new StringBuilder("command: ")
                .append(arguments.path("command").asText(""));
        String cwd = arguments.path("cwd").asText("");
        if (!cwd.isBlank()) {
            sb.append("\ncwd: ").append(cwd);
        }
        return sb.toString();
    }

    /**
     * write_file 的 diff：现有文件内容 → 新内容；文件不存在视为新建。
     */
    private String diffWithExistingFile(String path, String newContent) {
        String oldContent = null;
        try {
            Path existing = Path.of(path);
            if (Files.exists(existing)) {
                oldContent = Files.readString(existing);
            }
        } catch (IOException e) {
            // 读不到现有内容时降级为展示新内容，不阻断审批流程
            log.warn("审批展示读取现有文件失败: path={}, error={}", path, e.getMessage());
            return "（无法读取现有文件内容：" + e.getMessage() + "）\n新内容：\n"
                    + truncate(newContent);
        }
        if (oldContent == null) {
            return "（新建文件）\n" + truncate(newContent);
        }
        return truncate(LineDiffUtils.unifiedDiff(oldContent, newContent));
    }

    /**
     * 审批详情截断：超长时保留头部并标注，保住 diff 头部（变更统计在最前）
     */
    private String truncate(String detail) {
        if (detail.length() <= MAX_DETAIL_CHARS) {
            return detail;
        }
        return detail.substring(0, MAX_DETAIL_CHARS)
                + "\n…（详情过长，已截断，完整内容以实际执行为准）";
    }
}
