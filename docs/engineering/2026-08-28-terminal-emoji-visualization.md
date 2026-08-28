# 终端过程可视化：全事件 emoji 前缀、思考流与 token 用量展示

- **日期**：2026-08-28
- **作者**：Macro Ray
- **状态**：已完成，95 个测试全部通过（追加批次，契约见 `docs/business/roadmap.md` 2.8 节）

## 任务范围

用户在批次①进行中提出：学习定位要求终端尽量展示 Agent 的每一步运行，
每类指令用 emoji 开头（🥷🏻 用户 / 🤖 回答 / 🛠️ 工具 / 💭 思考 / ✍🏻 写入 /
🔍 搜索 / 📊 token 等），并授权按同风格补充词表。
本次将渲染层改造为「全事件 emoji 化」，并补齐 💭 思考与 📊 用量的数据来源。

## 变更点

### 渲染层

| 文件 | 变更 |
|------|------|
| `cli/CliRenderer.java` | 接口新增 5 方法：`displayUser` / `displayAssistantDelta` / `displayReasoningDelta` / `displayAssistantEnd` / `displayTokenUsage` |
| `cli/TerminalRenderer.java` | 重写：emoji 词表 + 按工具类型分派（✍🏻 写、📖 读、🗂️ 列目录、🔍 搜、⚙️ 命令，未登记默认 🛠️）；工具参数回显截断 120 字符；流式状态机（`reasoningActive` / `assistantOpen` 两标志管理「思考行开→收→开回答行」，`displayAssistantEnd` 收尾复位） |

### 数据来源（💭 与 📊）

| 文件 | 变更 |
|------|------|
| `model/ChatResponse.java` | **顺修既有隐患**：`Usage` 三个字段与 `finishReason` 补 `@JsonProperty` snake_case 映射——此前 ObjectMapper 未启用蛇形策略，`prompt_tokens` 映射不进来，用量解析恒为 0 |
| `client/StreamCallback.java` | 新增 default `onReasoningDelta`（GLM、DeepSeek 系模型的 `delta.reasoning_content`；OpenAI 官方模型无此字段，不触发）与 default `onUsage` |
| `client/OpenAiStreamingChatClient.java` | 请求携带 `stream_options: {"include_usage": true}`；解析流末尾**不含 choices** 的 usage 统计事件（原代码会将其当空事件跳过）；解析思考增量 |
| `agent/ToolCallListener.java` | 新增 default `onRoundUsage`：回合（一次 run，可含多次 LLM 调用）结束时回调用量合计 |
| `agent/AgentLoop.java` | 同步/流式两模式累计各次 LLM 调用的 usage，回合完成时统一回调；流式代理转发思考增量 |

### REPL 接线

| 文件 | 变更 |
|------|------|
| `cli/Repl.java` | 输入回显 🥷🏻；流式渲染全部改走 renderer（删除本地 CYAN/RESET 拼接与从未使用的 `fullContent` 死代码）；listener 累计会话级 token 并渲染 📊；**顺修**流式错误「onError 渲染一次 + 异常 catch 再渲染一次」的重复显示——onError 现只负责关闭流式块 |

### 测试（新增 6 个，总计 95 全绿）

- `AgentLoopTest`：同步多轮 usage 汇总回调（100+200=300）；流式 usage 跨迭代汇总
- `model/ChatResponseTest`（新）：锁定 usage / finish_reason 的 snake_case 映射，防回归
- `client/OpenAiStreamingChatClientTest`（新）：MockWebServer 模拟 SSE 流——思考、文本、usage 三类增量与请求体 `include_usage` 断言（首个流式覆盖）
- `cli/TerminalRendererTest`（新）：emoji 映射与默认值
- `cli/ReplTest`：打桩渲染器同步新接口方法

## 验证结果

- `JAVA_HOME=D:/Java/jdk-21 mvn -B test`：**95 个测试全部通过，0 失败**（新增 6）。
- `mvn compile` 无警告；编译验证 TerminalRenderer 状态机与接口实现完整。

## 追加修复（同日实测反馈）

用户真实终端实测发现两个问题，当轮修复：

### 1. 内联 `<think>` 标签未剥离（💭 失效）

**现象**：GLM 兼容端点把思考过程以内联 `<think>...</think>` 标签混在 content 里传输
（而非 `reasoning_content` 字段），思考原文带标签一起显示在 🤖 行内。

**修复**：新增 `client/ThinkContentFilter`（包私有，状态机）——
- 判定期：流开头缓冲凑齐 7 字符确认是否 `<think>` 开头；不是则 `decided` 走零缓冲直通快速路径；
- 思考期：内容路由到 `onReasoningDelta`；每次只放行"末尾最长 `</think>` 前缀"之外的部分，防闭合标签跨增量被截断；
- 正文期：闭标签后全部回 `onContentDelta`；`flush` 兜底处理未闭合残留与判定残字。
- 同步端点同样处理：`OpenAiChatClient` 返回前调 `stripThinkBlock` 剥离。
- **关键约束**：思考段不得进入 `contentBuilder`（对话历史），否则标签会随历史回传污染上下文。

**测试**：`ThinkContentFilterTest` 7 个（含标签跨增量分裂、正文字面 `<think>` 不误伤、未闭合兜底）+
流式端到端 1 个（GLM 风格 SSE）+ 同步剥离 1 个。

### 2. 📊 统计行粘连在回答末行

**现象**：流式回答的最后一个 delta 打印后回答块未收尾，
AgentLoop 回调 `onRoundUsage` 时 📊 直接接在末行（`随时开始吧！📊 本轮 token：…`）。

**修复**：Repl 引入 `pendingRoundUsage` 暂存——listener 回调只累计会话用量并暂存，
渲染推迟到回答块关闭之后（流式 `displayAssistantEnd` 后 / 同步 `displayAssistant` 后），
统一保证"先答案、后统计"版式；回合异常时清除暂存，`/clear` 一并重置。

### 修复后验证

- `mvn -B test`：**104 个测试全部通过，0 失败**（较初版新增 9）。

### 3. 工具 emoji 统一、行纪律与字面 \n（同日二次实测反馈）

用户以真实多工具会话实测，反馈三个渲染问题，当轮修复：

**现象**：
1. 工具调用按类型分派 emoji（⚙️/📖/✍🏻…），用户拍板改为**统一 🛠️**——
   emoji 应代表"系统的一类反应"，不按工具细分；
2. 叙述文字与工具调用行粘连在同一行、💭 与 🤖 块互相插队、回答块首悬挂空行
   ——流式块与独立行事件之间没有行纪律，多轮工具调用后状态机互相污染；
3. 终端出现字面 `\n`：工具结果被 `replace("\n", "\\n")` 压成单行时，
   换行全部变成了字面反斜杠n打进终端（经 curl 抓原始 SSE 佐证：
   服务端 content 增量里的换行都是真换行，字面 `\n` 全部来自该处）。

**修复**（`TerminalRenderer` 状态机重写 + `CliRenderer` 注释同步）：
- **emoji 语义**：删除 `TOOL_EMOJI` 映射与 `emojiFor`，工具调用统一 🛠️；
- **行纪律**：独立行事件（🛠️/✅/❌）打印前先 `closeStreamingBlock()`
  （未关闭的流式块补 RESET + 换行）；💭 开启前先收掉未关闭的 🤖 块；
  回答块开启时剥掉模型输出的前导换行（`</think>\n\n` 场景），
  首个增量若纯换行则不空开 🤖 块；
- **工具结果摘要**：换行（含连续换行）折叠为单个 ⏎，超 200 字符截断并
  标注"（共 N 字符）"；结果全文仍完整保留在对话历史中，只影响终端回显。

**设计口径**：emoji/行距属展示层契约——"每类反应独占一行（块）、互不粘连"；
模型输出的真实换行原样保留，展示层仅做块首前导换行剥离与结果单行摘要。

**测试**：`TerminalRendererTest` 由 2 个 emoji 映射断言重写为 8 个行为测试
（统一 emoji、收块不粘连、⏎ 折叠不输出字面 \n、截断标注、前导换行剥离、
纯换行不空开块、💭 开启前收 🤖 块、空结果摘要）。

### 修复后验证

- `mvn -B test`：**112 个测试全部通过，0 失败**。

## 遗留风险与后续工作

- **emoji 字形依赖终端字体**：启动脚本已固化 UTF-8，Windows Terminal / 现代终端正常；
  经典 conhost + 部分字体可能显示为方框，属展示层限制，未做降级开关（需要时可加 `GREENSAM_EMOJI=false`）。
- **流式 usage 依赖服务端支持** `stream_options`：不支持的兼容服务端可能忽略（无 📊）或报错；
  报错场景需实测确认后决定是否加开关。
- `reasoning_content` 与内联 `<think>` 都只做展示/剥离，不入对话历史（OpenAI 格式要求不回传思考内容）。
- 横幅显示 v1.0.0 与 pom 版本 0.0.1-SNAPSHOT 不一致（用户手改的横幅），未处理。
- `ThinkContentFilter` 只剥离"流开头"的思考段；若模型在正文中间输出字面 `<think>`，按普通文本透传（与流式判定口径一致）。
