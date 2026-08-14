package com.greensamcli.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.greensamcli.agent.Tool;
import com.greensamcli.agent.ToolExecutionException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * "执行命令"工具——让 LLM 能够在本地执行 shell 命令。
 *
 * <p>这是 agent 能力的放大器：有了它，agent 写完代码就能自己跑测试、编译、git 操作，
 * 形成「改 → 验证」闭环。没有它，agent 的产出无法自主验证。</p>
 *
 * <p><b>安全模型</b>：采用「硬黑名单 + 放行」。{@link #DENYLIST} 以正则匹配命令，
 * 命中即硬拦截（抛 {@link ToolExecutionException}，不可绕过），覆盖灾难性 / 不可逆操作
 * （递归删除根目录、格式化、写裸盘、fork bomb、关机重启等）。</p>
 *
 * <p><b>关于交互式审批</b>：逐命令的"是否允许执行"确认属横切关注点，应由上层权限系统
 * 统一负责（如 Repl 层审批、diff 预览等），不在工具层掺入——与 {@link WriteFileTool}
 * 的安全哲学一致。黑名单仅兜底最危险的操作，并非完备的访问控制；交互式审批
 * 留作后续「权限系统」任务实现。</p>
 *
 * <p><b>平台封装</b>：Windows 经 {@code cmd /c}、类 Unix 经 {@code sh -c} 执行，
 * 以支持管道 / 重定向 / 链式命令等 shell 特性。</p>
 *
 * <p><b>护栏</b>：超时强杀进程（默认 {@value #DEFAULT_TIMEOUT_SECONDS}s）、
 * stdout/stderr 各截断到 {@value #MAX_OUTPUT_CHARS} 字符（保留头尾），
 * 避免构建日志等超长输出撑爆上下文。</p>
 *
 * @author Macro Ray
 * @since 2026-08-13
 */
@Slf4j
public class ExecuteCommandTool implements Tool {

    /** 默认超时秒数。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /** 单路输出（stdout / stderr）的字符上限，超出保留头尾各一半。 */
    private static final int MAX_OUTPUT_CHARS = 30000;

    /**
     * 危险命令黑名单（正则，对原始命令串 find 匹配）。
     * <p>仅兜底灾难性 / 不可逆操作，非完备访问控制——完备控制由上层权限系统负责。</p>
     * <ul>
     *   <li>递归删除根目录 / 家目录 / 全部（{@code rm -rf /}、{@code rm -rf ~}、{@code rm -rf *}）</li>
     *   <li>格式化文件系统（{@code mkfs...}、{@code format X:}）</li>
     *   <li>写裸设备（{@code dd of=/dev/...}、{@code > /dev/sdX}）</li>
     *   <li>fork bomb（{@code :(){ :|:& };:}）</li>
     *   <li>关机 / 重启</li>
     * </ul>
     */
    private static final List<Pattern> DENYLIST = List.of(
            // rm -rf / | rm -rf ~ | rm -rf $HOME | rm -rf /* （递归删根 / 家目录 / 全部）
            Pattern.compile("\\brm\\b.*(-rf|-fr|-r\\w*f|--recursive).*(^|[\\s;|&])(/|~|\\$HOME|/\\*)(\\s|$|;)"),
            // mkfs.*  格式化
            Pattern.compile("\\bmkfs(\\.\\w+)?\\b"),
            // Windows format X:
            Pattern.compile("\\bformat\\b\\s+[A-Za-z]:"),
            // dd 写裸盘 / 重定向到裸设备
            Pattern.compile("\\bdd\\b.*\\bof=/dev/"),
            Pattern.compile(">\\s*/dev/(sd|nvme|hd|disk|mmcblk)"),
            // fork bomb  :(){ :|:& };:
            Pattern.compile(":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:&\\s*\\}\\s*;\\s*:"),
            // 关机 / 重启
            Pattern.compile("\\b(shutdown|reboot|halt|poweroff|init\\s+0)\\b")
    );

    @Override
    public String getName() {
        return "execute_command";
    }

    @Override
    public String getDescription() {
        return "Execute a shell command and return its stdout, stderr and exit code. "
                + "Runs via cmd /c on Windows, sh -c on Unix. Catastrophic commands are blocked.";
    }

    /**
     * 参数 Schema：command 必填，cwd / timeout_seconds 可选。
     */
    @Override
    public JsonNode getParameters() {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("type", "object");

        ObjectNode properties = JsonNodeFactory.instance.objectNode();

        ObjectNode cmdProp = JsonNodeFactory.instance.objectNode();
        cmdProp.put("type", "string");
        cmdProp.put("description", "The shell command to execute (may use pipes, redirects, chaining)");
        properties.set("command", cmdProp);

        ObjectNode cwdProp = JsonNodeFactory.instance.objectNode();
        cwdProp.put("type", "string");
        cwdProp.put("description", "Working directory. Defaults to the process current directory.");
        properties.set("cwd", cwdProp);

        ObjectNode timeoutProp = JsonNodeFactory.instance.objectNode();
        timeoutProp.put("type", "integer");
        timeoutProp.put("description", "Timeout in seconds. Process is killed on expiry. Default 120.");
        properties.set("timeout_seconds", timeoutProp);

        params.set("properties", properties);
        params.putArray("required").add("command");
        return params;
    }

    /**
     * 执行 shell 命令。
     *
     * <p>执行流程：解析参数 → 黑名单校验 → 解析 cwd → 平台 shell 封装 →
     * 启动进程 → 并发排空 stdout/stderr → 等待（超时强杀）→ 截断输出 → 格式化结果。</p>
     *
     * @param arguments 含 {@code command}，可选 {@code cwd} / {@code timeout_seconds}
     * @return {@code exit_code=N} + stdout + stderr（超时时附 timeout 提示，含已收集的部分输出）
     * @throws ToolExecutionException 命中黑名单、cwd 非法、启动失败时抛出
     */
    @Override
    public String execute(JsonNode arguments) throws ToolExecutionException {
        String command = arguments.get("command").asText();
        if (command.isBlank()) {
            throw new ToolExecutionException("command must not be empty");
        }

        // 黑名单硬拦截
        checkDenylist(command);

        String cwdStr = arguments.has("cwd") && !arguments.get("cwd").isNull()
                ? arguments.get("cwd").asText() : null;
        int timeoutSeconds = arguments.has("timeout_seconds") ? arguments.get("timeout_seconds").asInt()
                : DEFAULT_TIMEOUT_SECONDS;

        Path cwd = resolveCwd(cwdStr);

        List<String> shellCommand = buildShellCommand(command);
        log.info("Executing command: cmd=[{}], cwd={}, timeout={}s", command, cwd, timeoutSeconds);

        Process process = startProcess(shellCommand, cwd);

        // 并发排空两路输出，避免管道缓冲写满导致子进程阻塞死锁
        OutputHolder stdout = new OutputHolder();
        OutputHolder stderr = new OutputHolder();
        Thread stdoutThread = new Thread(() -> drain(process.getInputStream(), stdout), "cmd-stdout");
        Thread stderrThread = new Thread(() -> drain(process.getErrorStream(), stderr), "cmd-stderr");
        stdoutThread.start();
        stderrThread.start();

        boolean finished = waitFor(process, timeoutSeconds);

        if (!finished) {
            // 超时：强杀进程，再 join 排空线程以回收已收集的部分输出
            log.warn("Command timed out after {}s, killing: cmd=[{}]", timeoutSeconds, command);
            process.destroyForcibly();
            joinSilently(process);
            joinSilently(stdoutThread);
            joinSilently(stderrThread);
            return formatTimeout(stdout.toString(), stderr.toString(), timeoutSeconds);
        }

        // 正常结束：先 join 排空线程，再读取输出。
        // waitFor 返回（进程退出）时，排空线程可能尚未读完末尾缓冲；若提前快照会偶发截断输出（竞态）。
        joinSilently(stdoutThread);
        joinSilently(stderrThread);

        int exitCode = process.exitValue();
        String outText = stdout.toString();
        String errText = stderr.toString();
        log.info("Command finished: exitCode={}, stdoutBytes={}, stderrBytes={}",
                exitCode, outText.length(), errText.length());

        return formatNormal(exitCode, outText, errText);
    }

    /**
     * 黑名单校验：任一正则命中即拒绝。
     */
    private void checkDenylist(String command) throws ToolExecutionException {
        for (Pattern p : DENYLIST) {
            if (p.matcher(command).find()) {
                throw new ToolExecutionException(
                        "Blocked by safety denylist (matches: " + p.pattern() + "): " + command);
            }
        }
    }

    /**
     * 解析 cwd：未提供则用进程当前目录；提供则校验存在且为目录。
     */
    private Path resolveCwd(String cwdStr) throws ToolExecutionException {
        if (cwdStr == null) {
            return Paths.get(".").toAbsolutePath().normalize();
        }
        Path cwd = Paths.get(cwdStr);
        if (!java.nio.file.Files.isDirectory(cwd)) {
            throw new ToolExecutionException("cwd is not a directory: " + cwdStr);
        }
        return cwd.toAbsolutePath().normalize();
    }

    /**
     * 按平台选择 shell 封装：Windows 用 cmd /c，其余用 sh -c。
     */
    private List<String> buildShellCommand(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows
                ? List.of("cmd", "/c", command)
                : List.of("sh", "-c", command);
    }

    /**
     * 启动子进程，失败包装为 ToolExecutionException。
     */
    private Process startProcess(List<String> command, Path cwd) throws ToolExecutionException {
        try {
            return new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(false)
                    .start();
        } catch (IOException e) {
            throw new ToolExecutionException("Failed to start command: " + e.getMessage(), e);
        }
    }

    /**
     * 等待进程结束（带超时）。中断时恢复标志并视作未完成。
     */
    private boolean waitFor(Process process, int timeoutSeconds) {
        try {
            return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 把输入流读尽写入 OutputHolder。读流在独立线程跑，NUL 字节等原样保留。
     */
    private static void drain(InputStream in, OutputHolder sink) {
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                sink.append(buffer, n);
            }
        } catch (IOException e) {
            sink.setError(e.getMessage());
        }
    }

    /**
     * 静默 join 线程 / 进程，仅用于收尾，忽略中断。
     */
    private static void joinSilently(Process process) {
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinSilently(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 格式化正常结束的结果。
     */
    private String formatNormal(int exitCode, String stdout, String stderr) {
        return "exit_code=" + exitCode
                + "\n--- stdout ---\n" + truncate(stdout)
                + (stderr.isBlank() ? "" : "\n--- stderr ---\n" + truncate(stderr));
    }

    /**
     * 格式化超时结果：标注被杀，附带已收集的部分输出。
     */
    private String formatTimeout(String stdout, String stderr, int timeoutSeconds) {
        return "exit_code=-1 (timed out after " + timeoutSeconds + "s, killed)"
                + "\n--- stdout ---\n" + truncate(stdout)
                + (stderr.isBlank() ? "" : "\n--- stderr ---\n" + truncate(stderr));
    }

    /**
     * 输出截断：超 MAX_OUTPUT_CHARS 时保留头尾各一半并标注，防止超长日志撑爆上下文。
     */
    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        int half = MAX_OUTPUT_CHARS / 2;
        return output.substring(0, half)
                + "\n... [truncated " + (output.length() - MAX_OUTPUT_CHARS) + " chars] ...\n"
                + output.substring(output.length() - half);
    }

    /**
     * 输出收集容器，供排空线程写入、主线程读取。
     */
    private static final class OutputHolder {
        private final StringBuilder sb = new StringBuilder();

        void append(byte[] buffer, int len) {
            sb.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
        }

        void setError(String message) {
            sb.append("[stream read error: ").append(message).append("]");
        }

        @Override
        public String toString() {
            return sb.toString();
        }
    }
}
