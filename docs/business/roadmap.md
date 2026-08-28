# greensam-cli 产品路线图与开发计划

- **日期**：2026-08-28
- **作者**：Macro Ray
- **状态**：活文档——随产品规划演进；本文档是当前所有设计共识的唯一权威记录

> 本文源自 2026-08-28 的一轮系统性设计拷问（逐项决策、逐项确认）。
> 开工任何批次前先读本文；**已拒绝项不得未经讨论重新立项**（见第五章）。

---

## 一、产品定位

**学习项目 + 求职简历项目 + 开源教学作品**，三合一：

- 按企业规范打磨（分层、测试、文档、交付纪律），作为项目经历支撑求职；
- 以开源为目标，但不求做成成熟产品——验收标准是**别人看得懂、能学到东西、能跑起来**；
- 目标读者：中文 Java 学习者。文档、注释、交互文案以简体中文为主。

---

## 二、设计决策记录（已确认契约）

### 2.1 协议范围：仅 OpenAI 兼容

只实现 OpenAI wire 格式（`tool_calls` / `role:"tool"` / SSE delta）。
**不抽 Provider 抽象层**——`ChatClient` / `StreamingChatClient` 接口已足够隔离协议实现。
理由：求职简历讲深度比讲广度值钱；避免过早抽象。

### 2.2 架构落位：核心循环与横切关注点分离

`AgentLoop` 对「审批、中断」保持无知，只感知两个抽象概念：
「工具执行前有人可能说不」「循环可能被取消」。具体策略全部在外面：

| 横切关注 | 落位 | 理由 |
|----------|------|------|
| 审批拦截 | `ToolRegistry.executeTool` 入口 | 「该不该执行这个工具」归注册表管，AgentLoop 保持纯粹 |
| 中断 | `AgentLoop.cancel()` 公开 API | Repl 只做 Ctrl+C → `cancel()` 的信号转换；cancel 逻辑可注入假 client 单测，不构造真实终端（JLine 测试挂起教训） |
| 扩展点设计时机 | 批次②动 AgentLoop 时**一次设计到位**（cancel API + 工具执行前后 hook），后续批次只加实现、不改结构 | 避免三个批次各凿一个洞，核心类腐化 |

### 2.3 中断机制契约（批次②）

- **执行中 Ctrl+C = 中断当前回合**（不是退出）：
  - 正在跑的子进程 `destroyForcibly` 强杀（Windows 下 `cmd /c` 的孙进程杀不掉，需 tree-kill——实现时记录到 docs/engineering）；
  - 未决的 tool_call 在历史中补一条「用户已中断」的 tool result，对话保持连续（可接着问「刚才做到哪了」）；
  - 终端提示「⏹ 已中断当前回合」，回到提示符。
- **空闲时 Ctrl+C 维持现状**（只清当前输入行）；**Ctrl+D 退出**不变。
- Esc 键中断方案不做（见第五章）。

### 2.4 上下文截断契约（批次③）

- **触发**：每次发送 LLM 前估算，超阈值才砍；
- **估算**：字符近似——CJK 按 1 字 ≈ 1 token、ASCII 按 4 字符 ≈ 1 token（保守高估；估算器放 `utils` 包，`final` + 私有构造）；
- **策略**：永远保住 system 消息 + 最近 N 条，从最旧的非 system 消息开始丢；
- **配置**：`GREENSAM_MAX_CONTEXT_TOKENS`，默认 16384（截断错杀比爆窗便宜）；
- **用户可见**：真发生截断时终端提示「（上下文超限，已丢弃最早 K 条消息）」，不许静默失忆；
- 摘要压缩（/compact）明确不做（见第五章）。

### 2.5 审批层契约（批次④）

- **范围**：`write_file` / `edit_file` / `execute_command` 三个写副作用工具需审批；
  四个读类工具（`read_file` / `list_files` / `grep` / `glob`）自动放行；
- **交互**：执行前展示**完整命令**（写工具展示**完整 diff**），用户三选：
  `[y]` 本次允许 / `[n]` 拒绝 / `[a]` 本会话内该工具不再询问；
- **拒绝语义**：`[n]` 不是抛异常，而是给 LLM 回一条「用户拒绝了该操作」的 tool result，
  让它有机会换方案——审批是给 agent 的反馈信号，不是刹车（Claude Code 同款语义）；
- **跳过开关**：`GREENSAM_AUTO_APPROVE=true` 或启动参数 `--yolo`；
- 顺带补全 `ExecuteCommandTool` 黑名单的 Windows 破坏性命令规则（`rd /s /q`、`del /f /s /q` 盘符等——现有正则只覆盖 Unix 系）；
- 路径白名单沙箱不做（见第五章），README「已知边界」诚实标注。

### 2.6 分发与快速开始修复（批次①）

- `maven-shade-plugin` 打 fat jar——现状 `java -jar` 缺 Class-Path 必然 `NoClassDefFoundError`，README 快速开始是假的；
- 附带启动脚本（`.cmd` / `.sh`），固化 UTF-8 编码参数等运行参数；
- README 重写：修正 5 处漂移（运行命令、日志组件 slf4j-simple→logback、测试数量描述、工具结构图 2→7、Ctrl+C 说法）、徽章（CI + License）、留一句「欢迎 issue」；
- 补 `.env.example`（新人 clone 后知道要配哪些键）；
- OkHttp 超时修复：共享 `OkHttpClient` 配置 read timeout，新增 `GREENSAM_TIMEOUT_SECONDS` 默认 300——现状默认 10s，非流式模式遇慢模型必 `SocketTimeoutException`；配 MockWebServer 慢响应测试；
- AGENTS.md 交付清单增加一条「用户可见文档（README）同步」。

### 2.7 开源配套

- **CI**：GitHub Actions，ubuntu + windows **双平台矩阵**跑 `mvn test`——`ExecuteCommandTool` 有平台分支，单平台测不出另一边的回归；
- README 纯中文（不做双语）；CONTRIBUTING.md 不写（单人教学项目）；
- 安全事实核查结论（2026-08-28）：`.gitignore` 覆盖完备（`.env` / `logs/` / `target/`），`.env` 全历史从未提交，MIT 协议已就位。

### 2.8 终端过程可视化契约（已完成，2026-08-28 追加）

学习定位的直接落点：终端尽量展示 Agent 的每一步运行，每类事件以 emoji 前缀渲染
（实现收敛在 `CliRenderer` / `TerminalRenderer`，事件经 `ToolCallListener` 与
`StreamCallback` 流入）：

| emoji | 事件 | 来源 |
|-------|------|------|
| 🥷🏻 | 用户输入 | Repl 回显 |
| 🤖 | Agent 回答 | 文本增量 / 完整回复 |
| 💭 | 思考 | `delta.reasoning_content` 字段，或正文内联 `<think>...</think>` 标签（GLM 系端点，`ThinkContentFilter` 跨增量剥离）；无思考输出则不出现 |
| 🛠️ / ✍🏻 / 📖 / 🗂️ / 🔍 / ⚙️ | 工具调用 | 按工具类型分派，未登记工具用默认 🛠️ |
| ✅ | 工具结果 | 截断展示 |
| 📊 | token 消耗 | 每轮 LLM 调用 usage 合计 + 会话累计；渲染保证"先答案、后统计"；流式需 `stream_options.include_usage` 支持 |
| ❌ / 💡 | 错误 / 系统消息 | 同风格补充 |

---

## 三、开发计划（建议批次）

> 顺序为建议，可在后续产品规划中调整。**唯一硬约束：批次④依赖批次②**
> （审批的暂停/恢复交互建立在中断的线程模型与扩展点之上）。
> 量级为粗估，供排期参考。

### 批次① 分发与文档修复（量级：约半天）——**已完成（2026-08-28）**

按 2.6 清单执行。独立、零依赖、立刻让「快速开始」变成真的。

**验收标准**：README 快速开始的每条命令在真实终端逐条可跑通；`mvn test` 全绿；CI 双平台绿。

**交付结果**：shade fat jar / 双平台启动脚本 / README 重写 / `.env.example` 中文化并纳入版本控制 / OkHttp 超时修复（89 测试全绿）/ CI 工作流就绪。变更详情见 `docs/engineering/2026-08-28-distribution-and-docs-fix.md`。

### 追加批次 终端过程可视化（量级：约 1 天）——**已完成（2026-08-28）**

按 2.8 契约执行，用户在批次①进行中提出（学习定位：看见 Agent 每一步）。

**验收标准**：`mvn test` 全绿（含 SSE 解析、用量汇总、emoji 映射新测试）；真实终端可见每步 emoji 输出。

**交付结果**：CliRenderer/TerminalRenderer 全事件 emoji 化；reasoning_content 与 usage 解析（顺修 Usage 漏标 @JsonProperty 恒为 0 的隐患）；AgentLoop 双模式用量汇总回调；95 测试全绿。变更详情见 `docs/engineering/2026-08-28-terminal-emoji-visualization.md`。

### 批次② 中断机制（量级：1~2 天）

按 2.2 / 2.3 契约执行，**本批完成 AgentLoop 扩展点的一次性设计**（cancel API + 工具执行前后 hook）。

**验收标准**：执行中 Ctrl+C 可中断（含正在跑的 `execute_command` 子进程）；中断后对话可继续；`cancel()` 有不依赖真实终端的单测；踩坑记录落 docs/engineering。

### 批次③ 上下文截断（量级：约 1 天，独立，可插队）

按 2.4 契约执行。

**验收标准**：估算器与截断策略单测覆盖边界（恰好阈值、system 保护、空历史）；截断发生时用户可见提示。

### 批次④ 审批层（量级：2~3 天，依赖批次②）

按 2.5 契约执行，含黑名单 Windows 规则补全。

**验收标准**：拦截判定与终端交互分离（拦截逻辑可单测）；`[n]` 拒绝后 LLM 能换方案继续；`--yolo` 全放行；README「已知边界」同步。

---

## 四、待规划功能（backlog，待产品规划逐项展开）

| 功能 | 一句话说明 | 挂接点 |
|------|-----------|--------|
| plan 模式 | 先读后写：制定计划 → 用户确认 → 再执行 | 系统提示词 + 批次④审批层 |
| DAG 工具编排 | 并行 tool_calls 按依赖关系调度执行 | `AgentLoop.executeTools`（现为顺序执行） |
| Memory 系统 | 跨会话记忆；与批次③截断互补（截断是丢弃式，Memory 是保留式） | 会话历史层 |
| 联网搜索 | 新工具，走 `AbstractTool` 声明式参数 | `tools/` + `GreensamCli` 注册 |
| MCP 支持 | `McpToolAdapter` 把 MCP server 包装为 `Tool` 注册 | `ToolRegistry` |
| 一次性命令模式 | `greensam-cli "prompt"` 非交互跑完即退；开源演示与 CI 冒烟可用 | 启动路径（可搭批次②的车） |

---

## 五、已拒绝项及理由（不得未经讨论重新立项）

| 拒绝项 | 理由 |
|--------|------|
| 路径白名单沙箱 | 教学版复杂度高、收益低；以 README「已知边界」诚实标注代替；黑名单兜底保留 |
| Esc 键中断 | JLine raw 模式监听跨平台坑深，Ctrl+C 契约够用 |
| 摘要压缩（/compact） | 等真实长会话痛点出现再做；先用粗糙截断把「必炸」变「偶尔降智」 |
| Provider 多协议抽象 | 深度优先于广度，避免过早抽象；`ChatClient` 接口已隔离协议实现 |
| README 双语 | 目标读者为中文学习者，双语维护成本大于收益 |
| CONTRIBUTING.md | 单人教学项目，README 留「欢迎 issue」即可 |

---

## 六、交付纪律（每批次通用）

1. 编译通过 + 既有测试全绿 + 本批变更点有对应测试或验证证据；
2. 变更记录落 `docs/engineering/`（任务范围、变更点、验证结果、遗留风险）；
3. 用户可见文档（README、本文档状态）同步更新；
4. 新想法先查第五章——已拒绝项不重新立项，确要翻案先更新本文档并注明理由。

---

## 更新记录

| 日期 | 变更 |
|------|------|
| 2026-08-28 | 初版：源自设计拷问共识，含四大批次契约、架构落位、待规划与已拒绝项 |
| 2026-08-28 | 批次①「分发与文档修复」完成：fat jar / 启动脚本 / README / .env.example / 超时修复 / CI |
| 2026-08-28 | 追加批次「终端过程可视化」完成：全事件 emoji 前缀（🥷🏻🤖💭🛠️✍🏻📖🗂️🔍⚙️✅📊❌💡）、reasoning/usage 解析、契约入 2.8 节 |
