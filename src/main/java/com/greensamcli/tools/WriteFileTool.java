package com.greensamcli.tools;

import com.greensamcli.agent.AbstractTool;
import com.greensamcli.agent.Param;
import com.greensamcli.agent.ToolExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * "写入文件"工具——让 LLM 能够创建或覆盖本地文件系统的文件内容。
 *
 * <p>当用户说"把这段代码写进 src/Main.java"时，LLM 会选择调用此工具，
 * 自动构造参数 {@code {"path": "src/Main.java", "content": "..."}}。</p>
 *
 * <p><b>写入语义</b>：创建或覆盖（create-or-overwrite）。目标文件不存在则新建，
 * 已存在则整体截断覆盖。父目录缺失时会自动创建，避免 LLM 额外发起目录创建调用。</p>
 *
 * <p><b>安全说明</b>：本工具对写入路径不做硬性限制（支持绝对路径与相对路径，
 * 相对路径以 JVM 工作目录为基准，与 {@link ReadFileTool} / {@link ListFilesTool} 一致）。
 * 路径访问控制（如项目外写入确认、敏感路径拦截）属于横切关注点，
 * 应由上层权限系统统一负责，不在工具层掺入——详见 README「后续完善方向-权限系统」。</p>
 *
 * <p>安全措施：单次写入字节数不得超过 {@link #MAX_WRITE_FILE_BYTES}，超限即拒，
 * 避免磁盘灌满与误覆盖。</p>
 *
 * @author Macro Ray
 * @since 2026-08-11
 */
@Slf4j
public class WriteFileTool extends AbstractTool<WriteFileTool.Args> {

    /**
     * 单次写入字节数上限。
     * <p>
     * 5MB 对常规代码生成 / 文档撰写完全够用，超过即拒，避免磁盘灌满与误覆盖。
     */
    private static final int MAX_WRITE_FILE_BYTES = 5 * 1024 * 1024;

    public WriteFileTool() {
        super(Args.class);
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "创建或覆盖文件（整体写入给定内容）。支持绝对或相对路径，单次写入上限 5MB。";
    }

    /**
     * 参数声明：path / content 必填。参数 Schema 由 {@link AbstractTool} 依据此 record 自动生成。
     */
    public record Args(
            @Param(value = "要创建或覆盖的文件路径（绝对或相对路径）", required = true) String path,
            @Param(value = "要写入的文本内容（UTF-8）。传入空字符串可清空文件", required = true) String content) {

        public Args {
            // content 缺失时容错为空串（支持清空文件场景）
            if (content == null) {
                content = "";
            }
        }
    }

    /**
     * 执行文件写入。
     *
     * <p>执行流程：取参数 → 目录冲突校验 → 内容大小校验 → 自动创建父目录 →
     * 写入文件（创建或截断覆盖）→ 记录变更日志。</p>
     *
     * @param args 已绑定的参数 record，content 已兜底为非 null
     * @return 写入结果描述：新建返回 {@code "已创建 <path>（<N> 字节）"}，
     *         覆盖返回 {@code "已覆盖 <path>（<N> 字节，原 <oldN> 字节）"}
     * @throws ToolExecutionException 目标路径是目录、内容超限或写入失败时抛出
     */
    @Override
    protected String doExecute(Args args) throws ToolExecutionException {
        String pathStr = args.path();
        Path path = Paths.get(pathStr);

        // 前置检查：目标路径若已是目录，禁止写入（避免误覆盖目录）
        if (Files.isDirectory(path)) {
            throw new ToolExecutionException("目标路径是目录: " + pathStr);
        }

        String content = args.content();

        // 大小校验：UTF-8 字节数超限即拒
        int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > MAX_WRITE_FILE_BYTES) {
            throw new ToolExecutionException(
                    "内容过大: " + contentBytes + " 字节，上限 " + MAX_WRITE_FILE_BYTES + " 字节");
        }

        // 写入前记录是否已存在，用于区分新建/覆盖，并在覆盖时保留旧字节数
        boolean existed = Files.exists(path);
        long oldBytes = existed ? sizeOf(path) : 0L;

        try {
            // 父目录缺失时自动创建（getParent 对单段相对路径返回 null，需防御）
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("写入文件失败: " + e.getMessage(), e);
        }

        // 关键写操作留 INFO 日志：路径、操作类型、新旧字节数（AGENTS.md 5.4）
        if (existed) {
            log.info("已覆盖文件: path={}, newBytes={}, oldBytes={}", path, contentBytes, oldBytes);
        } else {
            log.info("已创建文件: path={}, bytes={}", path, contentBytes);
        }

        return existed
                ? "已覆盖 " + pathStr + "（" + contentBytes + " 字节，原 " + oldBytes + " 字节）"
                : "已创建 " + pathStr + "（" + contentBytes + " 字节）";
    }

    /**
     * 读取文件大小，失败时按 0 记录（仅用于覆盖场景的日志/返回值，不阻断主流程）。
     */
    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("读取旧文件大小失败，按 0 处理: path={}, err={}", path, e.getMessage());
            return 0L;
        }
    }
}
