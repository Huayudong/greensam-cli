# greensam-cli

[![CI](https://github.com/Huayudong/greensam-cli/actions/workflows/ci.yml/badge.svg)](https://github.com/Huayudong/greensam-cli/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

一个用 Java 从零构建的 CLI Agent，灵感来自 [Claude Code](https://docs.anthropic.com/en/docs/claude-code) 和 [OpenAI Codex CLI](https://github.com/openai/codex)。

通过亲手实现来深入理解 CLI Agent 的核心原理：Agent Loop、Tool Calling、流式输出、终端交互。

---

## Agent CLI 核心原理

### 什么是 Agent？

传统聊天机器人是 **单轮问答**：用户提问 → LLM 回答。

Agent 的本质区别在于 **Tool Calling（工具调用）**：LLM 不仅能生成文本，还能决定调用外部工具，读取工具结果后继续推理，直到得出最终答案。

### Agent Loop — Agent 的心脏

所有 CLI Agent（Claude Code、Codex、本项目）共享同一个核心循环：

```
用户输入
    │
    ▼
┌───────────────────────────────────────┐
│  将对话历史 + 工具定义 发送给 LLM     │
└───────────────────────────────────────┘
    │
    ▼
┌─────────────────┐
│  LLM 返回响应   │
└─────────────────┘
    │
    ▼
┌──────────────────────┐    否      ┌──────────────────┐
│  包含 tool_calls？   │ ─────────▶ │  输出文本给用户  │
└──────────────────────┘            └──────────────────┘
    │ 是
    ▼
┌────────────────────┐
│  逐个执行工具      │
│  read_file → 结果  │
│  list_files → 结果 │
└────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│  将工具结果追加到对话历史            │
│  回到顶部，重新发送给 LLM            │
└──────────────────────────────────────┘
```

关键点：

- LLM 自己决定 **是否**调用工具、调用 **哪个**工具、传什么 **参数**
- 工具执行结果作为新消息回传给 LLM，LLM 基于结果继续推理
- 循环直到 LLM 不再调用工具（`finish_reason: "stop"`）
- 需要 `maxIterations` 上限防止无限循环

### Tool Calling 协议（OpenAI 格式）

LLM 如何表达"我想调用工具"？通过 `tool_calls` 字段：

```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [
    {
      "id": "call_abc123",
      "type": "function",
      "function": {
        "name": "read_file",
        "arguments": "{\"path\":\"/tmp/test.txt\"}"
      }
    }
  ]
}
```

工具执行结果如何回传？用 `role: "tool"` 消息：

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "文件内容..."
}
```

**注意**：`function.arguments` 是 JSON 字符串，不是 JSON 对象。需要二次解析。

### 流式输出（Streaming）

普通 API 调用等整个响应生成完才返回。SSE 流式传输让你 **边生成边显示**：

```
data: {"choices":[{"delta":{"content":"Hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
data: [DONE]
```

每个 `data:` 行是一个增量（delta），客户端拼接后形成完整响应。这就是 Claude Code 那种"打字机效果"的来源。

---

## 项目架构

### 分层设计

```
┌───────────────────────────────────────────────────┐
│  CLI 层 (cli/)                                    │
│  Repl → JLine3 终端循环                            │
│  TerminalRenderer → ANSI 彩色输出                  │
├───────────────────────────────────────────────────┤
│  Agent 层 (agent/)                                │
│  AgentLoop → 核心推理循环                          │
│  AbstractTool / ToolRegistry → 声明式工具基类与注册 │
├───────────────────────────────────────────────────┤
│  Client 层 (client/)                              │
│  OpenAiChatClient → 同步 API 调用                  │
│  OpenAiStreamingChatClient → SSE 流式调用          │
├───────────────────────────────────────────────────┤
│  Model 层 (model/)                                │
│  ChatMessage, ToolCall, ChatRequest, ChatResponse │
└───────────────────────────────────────────────────┘
```

依赖方向严格单向： **CLI → Agent → Client → Model**。Agent 层不依赖终端，可以脱离 REPL 进行单元测试。

### 核心组件详解

#### AgentLoop（agent/AgentLoop.java）

整个项目最核心的类。实现了上述 Agent Loop：

1. 接收用户输入，追加到对话历史
2. 调用 LLM API（同步或流式）
3. 如果响应包含 `tool_calls`：
    - 通知 `ToolCallListener`（用于 UI 显示）
    - 通过 `ToolRegistry` 查找并执行对应工具
    - 将工具结果作为 `role: "tool"` 消息追加到历史
    - 回到步骤 2
4. 如果响应是纯文本：返回给用户

内置 `MAX_ITERATIONS = 20` 防止无限循环。支持同步（`run`）和流式（`runStreaming`）两种模式。

#### AbstractTool 与声明式参数（agent/）

每个工具继承 `AbstractTool<Args>`，用一个参数 record + `@Param` 注解声明参数：

```java
public record Args(
        @Param(value = "要执行的 shell 命令", required = true) String command,
        @Param("超时秒数，默认 120") @JsonProperty("timeout_seconds") Integer timeoutSeconds) { ... }
```

同一份注解同时驱动 JSON Schema 生成（发给 LLM）与参数绑定（接收 LLM 的调用），
Schema 与解析共用单一事实来源，不会漂移。

#### ToolRegistry（agent/ToolRegistry.java）

工具注册表：管理所有 `Tool`，自动转换为 OpenAI API 需要的 `ToolDefinition` 格式。

#### OpenAiChatClient（client/OpenAiChatClient.java）

基于 OkHttp 的 API 客户端：

- 构建请求体（Jackson 序列化）
- 设置 `Authorization: Bearer` 头
- 解析响应为 `ChatResponse` 对象
- 错误处理（401 认证失败、429 限流、500 服务端错误）

#### OpenAiStreamingChatClient（client/OpenAiStreamingChatClient.java）

SSE 流式实现：

- 请求体添加 `"stream": true`
- 逐行读取 `data:` 前缀的 SSE 事件
- 拼接 `delta.content` 片段
- 拼接 `delta.tool_calls` 片段（工具调用也是增量传输的）
- 通过 `StreamCallback` 回调实时通知调用方

#### Repl（cli/Repl.java）

基于 JLine3 的终端 REPL：

- 箭头键浏览历史、Ctrl+C 中断当前输入行、Ctrl+D 退出
- 内置命令：`/help`、`/clear`、`/exit`
- 终端过程可视化：每类事件带 emoji 前缀（见下节），流式模式下思考与回答逐字显示

---

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 1. 构建 fat jar

```bash
mvn clean package -DskipTests
```

### 2. 配置 API Key

```bash
cp .env.example .env
# 编辑 .env，填入你的 OPENAI_API_KEY
```

支持任何兼容 OpenAI 格式的 API：通过 `OPENAI_BASE_URL` 指向中转、网关或本地部署。

### 3. 运行

```bash
# Windows（PowerShell / CMD）
.\bin\greensam-cli.cmd

# 类 Unix
./bin/greensam-cli.sh
```

启动脚本内部即 `java -jar`（含 UTF-8 编码参数，避免 Windows 控制台中文乱码），等价于：

```bash
java -jar target/greensam-cli-0.0.1-SNAPSHOT.jar
```

### 环境变量

| 变量                       | 必须 | 默认值                      | 说明                             |
|----------------------------|------|-----------------------------|----------------------------------|
| `OPENAI_API_KEY`           | 是   | -                           | API 密钥                         |
| `OPENAI_BASE_URL`          | 否   | `https://api.openai.com/v1` | API 地址（可用于兼容接口）       |
| `GREENSAM_MODEL`           | 否   | `gpt-4o`                    | 模型名称                         |
| `GREENSAM_SYSTEM_PROMPT`   | 否   | 内置提示词                  | 系统提示词                       |
| `GREENSAM_STREAMING`       | 否   | `true`                      | 是否启用流式输出                 |
| `GREENSAM_TIMEOUT_SECONDS` | 否   | `300`                       | HTTP 读超时秒数（非流式慢模型用）|

配置优先级：系统环境变量 > `.env` 文件 > 内置默认值。

### REPL 命令

| 命令     | 说明               |
|----------|--------------------|
| `/help`  | 显示帮助           |
| `/clear` | 清空对话历史并清屏 |
| `/exit`  | 退出程序           |

### 终端过程展示

为了让 Agent 链路的每一步都可见，所有事件都用 emoji 前缀渲染
（实现见 `cli/TerminalRenderer.java`；一个 emoji 代表系统的一类反应，
每类事件独占一行/块，互不粘连）：

| emoji | 含义 | 说明 |
|-------|------|------|
| 🥷🏻 | 用户 | 回显本轮输入 |
| 🤖 | Agent 回答 | 同步整段显示；流式逐字打印（为一个可跨多行的段落块） |
| 💭 | 思考 | 推理模型的思考过程：独立的 `reasoning_content` 字段，或正文内联 `<think>...</think>` 标签（GLM 系，自动剥离）；无思考输出则不出现 |
| 🛠️ | 调用工具 | 所有工具统一使用（参数回显超 120 字符截断） |
| ✅ | 工具结果 | 单行摘要：换行折叠为 ⏎，超 200 字符截断并标注原始字符数；全文仍在对话上下文中 |
| 📊 | token 消耗 | 每轮 LLM 调用后的输入/输出用量与会话累计 |
| ❌ | 错误 | API 错误、工具执行失败等 |
| 💡 | 系统消息 | 启动提示、清空历史等 |

📊 的前提：服务端返回 `usage`。同步模式天然支持；流式模式请求会携带
`stream_options: {"include_usage": true}`，个别不支持的兼容服务端可能忽略（看不到 📊）或报错。

### 运行测试

```bash
mvn test
```

测试套件覆盖 JSON 序列化、API 客户端（含超时行为）、Agent 循环、工具执行、配置解析，
由 CI 在 **ubuntu + windows 双平台**矩阵验证（`ExecuteCommandTool` 有平台分支，单平台测不出回归）。
测试数量以 `mvn test` 实际输出为准。

---

## 已知边界

诚实标注当前版本的能力边界（详细规划见 [docs/business/roadmap.md](docs/business/roadmap.md)）：

- **无交互式审批**：LLM 请求的写文件 / 执行命令操作当前直接执行，无需确认（审批层规划中）；
- **无路径沙箱**：读写与命令执行没有工作目录边界，请只在可信目录中运行；
- **命令黑名单仅为兜底**：拦截 `rm -rf /` 等灾难性命令，但可被构造绕过，**不是访问控制**；
- **执行中无法中断**：Agent 回合进行中（含正在运行的命令）暂无法通过 Ctrl+C 终止（中断机制规划中）；
- **对话历史无上限**：长会话会持续增长直至超出模型上下文窗口（上下文管理规划中）。

---

## 项目结构

```
src/main/java/com/greensamcli/
├── GreensamCli.java                    # main 入口，组装所有组件
├── model/
│   ├── ChatMessage.java                # 聊天消息（4种角色）
│   ├── ToolCall.java                   # tool_call 数据结构
│   ├── ToolDefinition.java             # OpenAI tools[] 格式
│   ├── ChatRequest.java                # 请求体
│   └── ChatResponse.java               # 响应体
├── client/
│   ├── ChatClient.java                 # 同步客户端接口
│   ├── StreamingChatClient.java        # 流式客户端接口
│   ├── StreamCallback.java             # 流式回调接口
│   ├── OpenAiChatClient.java           # OkHttp 同步实现
│   └── OpenAiStreamingChatClient.java  # SSE 流式实现
├── agent/
│   ├── Tool.java                       # 工具接口
│   ├── AbstractTool.java               # 声明式参数基类（Schema 生成 + 参数绑定）
│   ├── Param.java                      # 参数描述注解
│   ├── ToolRegistry.java               # 工具注册表
│   ├── ToolCallListener.java           # 工具执行回调
│   ├── ToolExecutionException.java     # 工具执行异常
│   └── AgentLoop.java                  # 核心 Agent 循环
├── tools/
│   ├── ReadFileTool.java               # 读取文件（截断超长文件）
│   ├── ListFilesTool.java              # 列出目录内容
│   ├── WriteFileTool.java              # 写入/创建文件
│   ├── EditFileTool.java               # 精确字符串替换编辑
│   ├── GlobTool.java                   # 按模式搜索文件名
│   ├── GrepTool.java                   # 正则搜索文件内容
│   └── ExecuteCommandTool.java         # 执行 shell 命令（黑名单兜底 + 超时强杀）
├── cli/
│   ├── CliRenderer.java                # 渲染接口
│   ├── TerminalRenderer.java           # ANSI 彩色终端输出
│   └── Repl.java                       # JLine3 REPL 循环
├── config/
│   ├── AppConfig.java                  # 环境变量配置加载
│   └── DotenvLoader.java               # .env 文件加载
└── utils/
    └── ToolSchemaUtils.java            # record → JSON Schema 生成
```

添加新工具只需继承 `AbstractTool` 声明参数 record，然后在 `GreensamCli.main()` 中注册。

---

## git 版本控制

```bash
git push origin master     # 只推 gitee

git push github master     # 只推 github

git push all master        # 同时推 gitee 和 github（并行推送）

git pull                   # 默认从 gitee 拉（origin/master）
```

---

## 后续方向

完整的路线图、设计契约与决策记录见 [docs/business/roadmap.md](docs/business/roadmap.md)，重点方向：

- **中断机制**：执行中 Ctrl+C 中断当前回合，含子进程强杀与对话状态修复
- **交互式审批**：写 / 执行前终端确认（y/n/always），diff 预览，拒绝可恢复
- **上下文管理**：token 估算 + 超限截断，防止长会话爆窗
- **MCP 支持**：通过 `McpToolAdapter` 接入任何 MCP 兼容的工具服务器

欢迎提 issue 交流与指正。

---

## 许可

[MIT](LICENSE)
