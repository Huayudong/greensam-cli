# 批次③：上下文截断

- **日期**：2026-09-18
- **作者**：Macro Ray
- **任务范围**：按 roadmap 2.4 契约落地上下文截断——发送 LLM 前估算历史 token 占用，超阈值则丢弃最旧的完整对话轮，终端可见提示；防止长会话因工具结果累积撑爆模型上下文窗口。

## 变更点

### 新增

| 文件 | 说明 |
|------|------|
| `utils/ContextTokenEstimator.java` | 字符近似 token 估算：非 ASCII 每 1 字 ≈ 1 token、ASCII 每 4 字符 ≈ 1 token（向上取整）、每条消息固定 8 token JSON 结构开销；`estimateMessages` 计入 content（null 安全）与 toolCalls 的函数名 / arguments。保守高估——估算偏高只多丢几条旧消息，偏低则爆窗 |
| `agent/ContextTruncator.java` | 纯函数截断策略：`truncate(history, maxTokens)` 返回 `TruncateResult(keptMessages, droppedCount)`。**按完整对话轮**（user → 下一条 user 之前）为删除单位，而非按条删除——保证不产生孤儿 tool 消息或没有 tool result 的 assistant-with-tool_calls（兼容端点会 400）。system 消息与最后一轮永不删；只剩最后一轮仍超限时放行请求（宁可降智不丢当前任务上下文） |
| `ContextTokenEstimatorTest`（9 用例） | 纯 ASCII / 纯 CJK / 混合取整 / null 安全 / 固定开销 / tool_calls 计入 |
| `ContextTruncatorTest`（10 用例） | 恰好阈值不截断、system 保护、逐轮丢弃、只剩最后一轮不删、删完仍超限放行、无孤儿 tool 消息、空历史 / null 安全、入参不被修改 |

### 修改

| 文件 | 说明 |
|------|------|
| `config/AppConfig.java` | 新增 `GREENSAM_MAX_CONTEXT_TOKENS`（默认 16384），非整数 fail-fast 报配置名，照 `GREENSAM_TIMEOUT_SECONDS` 三件套惯例 |
| `agent/AgentLoop.java` | 新增 6 参构造（`maxContextTokens` 注入；原 4/5 参便捷构造委托 `NO_CONTEXT_LIMIT` 哨兵值，测试夹具等场景行为不变）；新增私有方法 `truncateContextIfNeeded`，插入同步 `executeLoop` 与流式 `executeLoopStreaming` 两条路径的取消安全点之后、发送之前；截断结果原地替换 `conversationHistory`（clear + addAll，因 `client.send` 持有活引用） |
| `agent/ToolCallListener.java` | 新增 default 方法 `onContextTruncated(droppedCount, estimatedTokens)`（仿 `onRoundUsage` 先例，null listener 与未实现者天然静默） |
| `cli/Repl.java` | 匿名监听器实现 `onContextTruncated` → `renderer.displaySystem("上下文超限，已丢弃最早 K 条消息（估算约 X tokens）")`（💡 系统消息通道，契约文案） |
| `GreensamCli.java` | 装配处传入 `config.getMaxContextTokens()` |
| `.env.example` / `README.md` | 配置项说明、已知边界（截断是止血不是压缩）、项目结构、后续方向同步 |

## 设计决策

1. **按轮删除而非按条删除**：OpenAI 兼容协议要求每个 tool_call 紧跟对应 tool 结果，按条删除可能在配对中间切开导致请求 400（roadmap 契约「永远保住 system + 最近 N 条」落为「system + 最后一轮」）。
2. **便捷构造不截断（`Long.MAX_VALUE` 哨兵）**：4/5 参老构造保持既有测试与调用方行为零变化；生产装配（`GreensamCli` → `AppConfig`）是唯一启用截断的路径，避免 `agent` 包反向依赖 `config` 包或两处硬编码默认值。
3. **截断回调走 `ToolCallListener` default 方法**：零改动 `CliRenderer` 接口，复用 💡 系统消息通道，与用量回调同一先例。

## 验证结果

- `JAVA_HOME=D:/Java/jdk-21 mvn test`：**189 个测试全绿**（基线 162 + 新增 27）。
- 验收边界覆盖：恰好阈值不截断、system 保护（仅 system 超限不删）、空历史 / null 安全（估算器与截断器各自覆盖）、截断后发送内容断言（`AgentLoopTest` 假 client 捕获 messages）。
- 首轮运行暴露 3 个测试侧问题并已修正：截断后历史断言漏算本轮最终回复的追加；「只剩最后一轮」用例误用两轮历史（第一轮删得掉，未触达目标分支，改用单轮并补充「删完仍超限放行」用例）；一处 kept 内容断言笔误。实现本身零改动。

## 遗留风险

- **单轮超限无法截断**：一轮内塞入超大工具结果（如 read_file 大文件）+ 超长回复仍可能超限失败；`ContextTruncator` 会放行并落 WARN 日志（「无可安全丢弃的对话轮」）。
- **估算非精确**：字符近似对个别模型（如强化 CJK 的 tokenizer）偏差较大；参数 `GREENSAM_MAX_CONTEXT_TOKENS` 可按所用模型调整。
- **截断提示的终端实测**：`Repl` 的监听器为 `run()` 内局部匿名类，单测不可触达（构造 JLine 终端是测试禁区），接线为三行直通代码，需真实终端会话中观察 💡 提示验证。

## 后续工作

- 规划批次至此全部收官；后续功能（plan 模式、DAG 工具编排、Memory 等）按 roadmap 交付纪律先展开契约再立项。
- Memory 系统（保留式）与截断（丢弃式）互补，若长会话「丢记忆」痛点显现可优先立项。
