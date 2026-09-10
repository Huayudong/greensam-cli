# 批次④：审批层与提问工具

- **日期**：2026-08-31
- **任务范围**：路线图批次④（契约见 `docs/business/roadmap.md` 2.5 节）——
  写副作用工具交互式审批（命令原文 / 完整 diff，y/n/a 三选）、拒绝回传 LLM、
  `--yolo` 跳过开关、Windows 破坏性命令黑名单补全；
  **契约扩展**（用户修订）：新增 `ask_user` 提问工具，四选交互
  （a=最契合项目的推荐答案、b/c=其他方向候选、d=自定义回答）。

## 一、变更点

### 1.1 ApprovalHook：挂载在批次②扩展点上的审批钩子

- 实现 `ToolExecutionHook`（批次②铺设，本批零改动挂载），注册于 `GreensamCli`；
- `beforeExecute`：读类工具（read_file/list_files/grep/glob/ask_user）直接放行；
  写副作用工具组装详情后委托 `UserInteraction.confirmOperation` 三选：
  - `APPROVE` → 放行；`APPROVE_ALWAYS` → 记入会话级集合，该工具后续不再询问；
  - `REJECT` → 返回拒绝理由文本，由 ToolRegistry 包装为 `ToolExecutionException`，
    AgentLoop 以既有机制作为 tool result 回传 LLM——**拒绝是反馈信号，不是刹车**；
- 详情组装：`execute_command` 给完整命令原文（含 cwd）；`edit_file` 给
  old_string → new_string 的 diff；`write_file` 读现有文件给出完整 diff
  （不存在则标注「新建文件」），diff 超 4000 字符截断；
- `autoApprove=true`（`GREENSAM_AUTO_APPROVE=true` 或 `--yolo`）时整体直通。

### 1.2 LineDiffUtils：审批展示的行级 diff

- LCS 行级比较 + 3 行上下文 hunk 聚合（相邻 hunk 间隔 ≤6 行合并）、
  输出 80 行截断标注；任一侧超 5000 行退化为纯统计摘要；
- null / 空串视为零行（新文件不产生幻影删除行）；CRLF/LF 归一。

### 1.3 AskUserTool：a/b/c/d 四选提问工具

- 新工具 `ask_user`（纯交互无副作用，不需要审批）：Agent 需要用户拍板时，
  下发 question + 3 个候选答案；
- 终端渲染 ❓ 块（a/b/c 候选 + d 自定义回答），选择 a/b/c 返回选项原文，
  d 进入自由输入；未作答（Ctrl+C）返回「用户本次未作答」引导 LLM 自行决策；
- 结果统一包装为「用户的选择：…请严格按用户的选择继续执行。」回传 LLM；
- `ToolSchemaUtils` 顺势扩展 `List<String>` 参数支持（schema `array` + `items:string`，
  绑定侧 Jackson 原生支持）。

### 1.4 CLI 层装配

- 新增 `UserInteraction` 接口（agent 包）与 `TerminalUserInteraction` 实现（cli 包）：
  GreensamCli 创建单例注入审批钩子、AskUserTool 与 Repl，Repl 终端就绪后
  `bind(lineReader, renderer)`——回合执行在主线程，执行到交互点时主线程空闲，
  复用 REPL 的 LineReader 重入读取，不需要额外线程；
- **中断语义**：审批/提问期间终端处于 raw 模式，Ctrl+C 是 JLine 按键
  （UserInterruptException），不产生中断信号——审批期间按 Ctrl+C = 拒绝/未作答，
  与回合执行中「中断当前回合」（常规模式信号路径）天然互不干扰；
- 渲染扩展：`CliRenderer.displayApproval`（⚠️ 黄色块）/ `displayQuestion`（❓ 块）。

### 1.5 ExecuteCommandTool 黑名单补 Windows 规则

- `rd`/`rmdir` 同时带 `/s` 与 `/q`（递归静默删整树，任意顺序、大小写不敏感）；
- `del` 同时带 `/f /s /q`（强制递归静默删除）；
- `rd`/`rmdir`/`del` 直接以盘符根为目标（如 `rd C:\`）；
- 存量 Unix 规则不变；黑名单仍为兜底，主防线是交互式审批。

### 1.6 配置与开关

- `AppConfig` 新增 `GREENSAM_AUTO_APPROVE`（默认 false，非 true/false 值 fail-fast）；
- `GreensamCli.hasYolo(args)` 识别 `--yolo`（区分大小写）；两者任一即全放行；
- 默认系统提示词追加 ask_user 使用指引（多方案场景先提问再执行）。

### 1.7 同日补充修复：List&lt;String&gt; 参数宽容解析

- 真实会话复现一次 `ask_user` 失败：模型无视 schema `items:string` 约束，
  把 options 传成 `[{"key":"a","value":"…"}]`，Jackson 反序列化 `List<String>`
  在 START_OBJECT 处失败，工具调用中断，LLM 降级为纯文本提问（结构化四选丢失）；
- `AbstractTool` 绑定前新增宽容解析（对全部 `List<String>` 参数生效，非 ask_user 特判）：
  数组元素含非字符串值时按 value/text/label/content/option/answer/key 优先级提取
  文本字段（key 排最后，往往只是字母编号），数字/布尔按字面值，null 丢弃，
  兜底转 JSON 字符串，并 WARN 留痕原始值便于追踪模型行为；
- `AskUserTool` 的 @Param 描述与工具描述补充字符串数组格式示例，降低模型传错概率；
- 参数形态评估结论：保持 `List<String>` 不变——a/b/c/d 字母编号是终端表现层职责
  （`CliRenderer`/`UserInteraction` 分配），LLM 生成 key 属冗余信息且可能与终端
  分配冲突；对象数组形态本身更易触发传错。

## 二、验证结果

| 验证项 | 方式 | 结果 |
|--------|------|------|
| 全量回归 | `mvn test` | **165 全绿**（原 127 + 新增 38，含 1.7 修复 3 例） |
| 审批三选语义 / 会话放行 / 拒绝回传 / autoApprove 直通 / diff 详情 | `ApprovalHookTest` 9 用例（假 UserInteraction，不碰终端） | 通过 |
| ask_user 四选 / 自定义回答 / 未作答降级 / 参数校验 / Schema 形状 | `AskUserToolTest` 7 用例 | 通过 |
| ask_user 传对象/数字元素的宽容解析（value 优先于 key、混合形态、null 丢弃） | `AskUserToolTest` 新增 3 用例 | 通过 |
| 行级 diff（增删改 / hunk 聚合 / 截断 / 超限退化 / CRLF 归一） | `LineDiffUtilsTest` 9 用例 | 通过 |
| Windows 黑名单新规则 + 反例（dir /s /q 等放行） | `ToolsTest` 新增 4 用例（真实子进程执行） | 通过 |
| `--yolo` 解析 | `GreensamCliTest` 2 用例 | 通过 |
| 真实终端全链路 | winpty 脚本化会话：write_file 触发 ⚠️ 审批（新建文件 + 内容预览）→ `y` → ✅ 实际写入 → LLM 确认；ask_user 触发 ❓ 四选 → `d` 自定义回答 / `a` 推荐项 → ✅ 结果回传 → LLM 按选择继续 | 全链路通过 |
| 与中断机制共存 | 审批读输入走 raw 模式按键路径，回合中断走常规模式信号路径 | 设计上互不干扰（见 1.4） |

## 三、遗留风险

1. **审批不构成安全边界**：LLM 可以先申请低危命令再借管道/间接形式完成高危动作；
   黑名单 + 审批都是防误不是防恶，README「已知边界」已声明；
2. **[a] 放行粒度是工具级**：本会话内放行 `execute_command` 后，任意命令（黑名单外）
   都不再询问——后续如需按命令前缀细粒度放行，扩展 `ApprovalHook` 的会话集合即可；
3. **diff 视图截断**：超大文件（>5000 行）只显示统计摘要，超大 diff 显示头 80 行，
   完整内容需用户自行查看文件；
4. winpty 脚本化验证仍受其代理缺陷影响（退出阶段断言，不影响会话内行为），
   「⏹/⚠️/❓」的最终像素建议真实终端手工复核一次。

## 四、后续工作

- 核心四批次（①分发 ②中断 ④审批 + 终端可视化）全部收官；
- backlog 待规划：plan 模式（审批层与 ask_user 已是其基础设施）、
  DAG 工具编排、Memory 系统、联网搜索、MCP 支持、一次性命令模式；
- 批次③上下文截断（独立，约 1 天）仍可随时插队。
