# 终端行为校验修复：控制台堆栈污染与 Windows 子进程编码

- 日期：2026-08-28
- 触发：对 CLI 终端行为做整轮实录校验（读文件失败自愈、删除/重建文件、命令探路），发现 2 个 P1 问题与 2 个 P2 改进点

## 任务范围

1. **P1-1** 工具执行失败的 Java 堆栈直打控制台，11 行堆栈插在 REPL 渲染行之间，破坏对话区（与 logback「保持对话区干净」设计目标冲突）。
2. **P1-2** `execute_command` 子进程输出固定按 UTF-8 解码，Windows cmd（代码页 936/GBK）输出的中文全部乱码（`'ls' 不是内部或外部命令`、`dir` 卷标行等）。
3. **P2-1** 模型在 Windows 上先试 `ls`/`pwd` 类 Unix 命令失败，白耗轮次。
4. **P2-2** `read_file("/1.md")` 失败信息不暴露解析后的真实落点（Windows 下盘符缺失路径按当前工作盘解析），模型需多轮探路自纠。

## 变更点

| 文件 | 变更 |
|------|------|
| `src/main/resources/logback.xml` | CONSOLE appender pattern 追加 `%nopex` 抑制堆栈输出；完整堆栈仅落文件 |
| `src/main/java/com/greensamcli/agent/AgentLoop.java` | 工具失败日志 `log.error(msg, e)` → `log.warn`，带 `tool=` 结构化字段便于回溯 |
| `src/main/java/com/greensamcli/tools/ExecuteCommandTool.java` | Windows 命令封装改为两层 cmd（详见下）；description 增加「Windows 请直接使用 cmd 语法」提示 |
| `src/main/java/com/greensamcli/tools/ReadFileTool.java` | 路径统一 `toAbsolutePath().normalize()`；文件不存在时错误信息附带解析后的绝对路径 |
| `src/test/java/com/greensamcli/tools/ToolsTest.java` | 新增中文回环、Windows 嵌套引号用例；fileNotFound 用例补「解析为」断言 |
| `src/test/java/com/greensamcli/LogbackConsolePatternTest.java` | 新增：锁定「控制台单行消息、无堆栈帧」契约（pattern 直接取自 logback.xml 资源，防双份漂移） |

### 两层 cmd 封装机制（P1-2 的关键结论）

最终形态：`cmd /c "chcp 65001 >nul & cmd /c \"<用户命令>\""`。

- **为什么不能同一实例内 `chcp 65001 >nul & <命令>`**：cmd 内建命令（dir/echo）的输出编码在进程启动时即已确定，同一实例内 chcp 对后续内建命令无效（实测：chcp 报告 `Active code page: 65001`，echo 仍输出 GBK 字节）。
- **为什么需要内层 cmd**：内层 cmd 在 chcp 之后启动，读取到 UTF-8 代码页，内建命令输出即为 UTF-8；外置程序（git 等）同样在 chcp 后启动，输出统一 UTF-8。
- **为什么不改解码端为 GBK**：git/node 等现代 CLI 向管道输出 UTF-8，GBK 解码会反向引入乱码；chcp 方案让全部输出收敛到 UTF-8，与项目全中文 UTF-8 约定一致。
- **为什么放弃「先单独 spawn chcp 再 spawn 命令」**：无控制台环境（surefire/CI）下两个子进程不共享控制台，chcp 无法传导（实测无效）；两层封装的 chcp 与内层 cmd 同树，不依赖外部控制台，任何环境都生效。

## 验证结果

- `mvn test`：**115 个测试全绿**（含新增 3 个用例与 LogbackConsolePatternTest）。
- 诊断矩阵（ProcessBuilder 实测字节级验证）：

| 场景 | 结果 |
|------|------|
| `echo 中文`（内建） | UTF-8 ✓ |
| `dir` 卷标/目录头（内建） | UTF-8 ✓ |
| 链式 `echo A & echo 中文B` | & 绑定内层，UTF-8 ✓ |
| `echo "引号中文"`（引号参数） | 引号原样保留，UTF-8 ✓ |
| `git log`（外置 + 中文提交信息） | UTF-8 ✓ |
| `findstr "x" file`（外置 + 引号参数） | 正常 ✓ |
| `exit 7` 退出码 | 跨两层传播 = 7 ✓ |

## 遗留风险

1. 用户命令以反斜杠结尾的极端形态（`dir \\server\share\`）在引号包装下理论上存在引号粘连风险；transcript 中模型常见形态已全部验证通过。
2. 超时强杀（`destroyForcibly`）只杀最外层 cmd，孙进程若存活会持有管道导致排空线程延迟返回——**既有问题**，两层封装后进程层级加深一层，后续考虑进程树级 kill（`taskkill /T`）。
3. 真实终端下 JVM 自带控制台，外层 cmd 的 `chcp 65001` 会将用户当前终端代码页切为 65001 并在该会话内保留；与 CLI 自身 UTF-8 输出契约一致，属良性副作用。

## 后续工作

- 超时进程树级终止（见遗留风险 2）。
- `WriteFileTool` / `EditFileTool` 错误信息对齐「解析为绝对路径」风格（一致性，可选）。
- 交互式权限审批系统（`ExecuteCommandTool` JavaDoc 已预留定位）。
