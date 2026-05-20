# greensam-cli

一个用 Java 从零构建的 CLI Agent，灵感来自 [Claude Code](https://docs.anthropic.com/en/docs/claude-code) 和 [OpenAI Codex CLI](https://github.com/openai/codex)。

通过亲手实现来深入理解 CLI Agent 的核心原理：Agent Loop、Tool Calling、流式输出、终端交互。

---

## Agent CLI 核心原理

### 什么是 Agent？

传统聊天机器人是**单轮问答**：用户提问 → LLM 回答。

Agent 的本质区别在于 **Tool Calling（工具调用）**：LLM 不仅能生成文本，还能决定调用外部工具，读取工具结果后继续推理，直到得出最终答案。

### Agent Loop — Agent 的心脏

所有 CLI Agent（Claude Code、Codex、本项目）共享同一个核心循环：

```
用户输入
    │
    ▼
┌─────────────────────────────────────┐
│  将对话历史 + 工具定义 发送给 LLM     │
└─────────────────────────────────────┘
    │
    ▼
┌─────────────────┐
│  LLM 返回响应     │
└─────────────────┘
    │
    ▼
┌──────────────────┐    否     ┌──────────────┐
│  包含 tool_calls？ │─────────▶│ 输出文本给用户  │
└──────────────────┘          └──────────────┘
    │ 是
    ▼
┌──────────────────┐
│  逐个执行工具      │
│  read_file → 结果  │
│  list_files → 结果 │
└──────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│  将工具结果追加到对话历史               │
│  回到顶部，重新发送给 LLM              │
└──────────────────────────────────────┘
```

关键点：
- LLM 自己决定**是否**调用工具、调用**哪个**工具、传什么**参数**
- 工具执行结果作为新消息回传给 LLM，LLM 基于结果继续推理
- 循环直到 LLM 不再调用工具（`finish_reason: "stop"`）
- 需要 `maxIterations` 上限防止无限循环

### Tool Calling 协议（OpenAI 格式）

LLM 如何表达"我想调用工具"？通过 `tool_calls` 字段：

```json
{
  "role": "assistant",
  "content": null,
  "tool_calls": [{
    "id": "call_abc123",
    "type": "function",
    "function": {
      "name": "read_file",
      "arguments": "{\"path\":\"/tmp/test.txt\"}"
    }
  }]
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

普通 API 调用等整个响应生成完才返回。SSE 流式传输让你**边生成边显示**：

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
┌─────────────────────────────────────────────────┐
│  CLI 层 (cli/)                                   │
│  Repl → JLine3 终端循环                           │
│  TerminalRenderer → ANSI 彩色输出                  │
├─────────────────────────────────────────────────┤
│  Agent 层 (agent/)                               │
│  AgentLoop → 核心推理循环                          │
│  Tool / ToolRegistry → 工具接口与注册               │
├─────────────────────────────────────────────────┤
│  Client 层 (client/)                             │
│  OpenAiChatClient → 同步 API 调用                  │
│  OpenAiStreamingChatClient → SSE 流式调用           │
├─────────────────────────────────────────────────┤
│  Model 层 (model/)                               │
│  ChatMessage, ToolCall, ChatRequest, ChatResponse │
└─────────────────────────────────────────────────┘
```

依赖方向严格单向：**CLI → Agent → Client → Model**。Agent 层不依赖终端，可以脱离 REPL 进行单元测试。

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

#### Tool 接口（agent/Tool.java）

每个工具只需实现 4 个方法：

```java
public interface Tool {
    String getName();                    // 工具名称，如 "read_file"
    String getDescription();             // 描述，LLM 根据此决定何时调用
    JsonNode getParameters();            // JSON Schema，定义参数结构
    String execute(JsonNode arguments);  // 执行逻辑，返回字符串结果
}
```

`ToolRegistry` 管理所有工具，自动将 `Tool` 转换为 OpenAI API 需要的 `ToolDefinition` 格式。

#### ChatMessage（model/ChatMessage.java）

用工厂方法创建不同角色的消息：

```java
ChatMessage.system("你是一个助手")           // 系统提示词
ChatMessage.user("读取 /tmp/test.txt")       // 用户输入
ChatMessage.assistant("文件内容是...")         // LLM 文本回复
ChatMessage.assistantWithToolCalls(toolCalls) // LLM 工具调用
ChatMessage.toolResult(id, name, content)     // 工具执行结果
```

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
- 箭头键浏览历史、Ctrl+C 中断、Ctrl+D 退出
- 内置命令：`/help`、`/clear`、`/exit`
- 流式模式下逐字符输出带 ANSI 颜色

### 项目结构

```
src/main/java/com/greensamcli/
├── GreensamCli.java                    # main 入口，组装所有组件
├── model/
│   ├── ChatMessage.java                # 聊天消息（4种角色）
│   ├── ToolCall.java                   # tool_call 数据结构
│   ├── ToolDefinition.java             # OpenAI tools[] 格式
│   ├── ChatRequest.java                # 请求体
│   └── ChatResponse.java              # 响应体
├── client/
│   ├── ChatClient.java                 # 同步客户端接口
│   ├── StreamingChatClient.java        # 流式客户端接口
│   ├── StreamCallback.java             # 流式回调接口
│   ├── OpenAiChatClient.java           # OkHttp 同步实现
│   └── OpenAiStreamingChatClient.java  # SSE 流式实现
├── agent/
│   ├── Tool.java                       # 工具接口
│   ├── ToolRegistry.java              # 工具注册表
│   ├── ToolCallListener.java          # 工具执行回调
│   ├── ToolExecutionException.java    # 工具执行异常
│   └── AgentLoop.java                 # 核心 Agent 循环
├── tools/
│   ├── ReadFileTool.java              # 读取文件（截断超长文件）
│   └── ListFilesTool.java             # 列出目录内容
├── cli/
│   ├── CliRenderer.java               # 渲染接口
│   ├── TerminalRenderer.java          # ANSI 彩色终端输出
│   └── Repl.java                      # JLine3 REPL 循环
└── config/
    └── AppConfig.java                 # 环境变量配置加载

src/test/java/com/greensamcli/
├── model/ChatMessageTest.java         # 4 tests — Jackson 序列化验证
├── client/OpenAiChatClientTest.java   # 3 tests — MockWebServer API 测试
├── agent/AgentLoopTest.java           # 6 tests — Agent 循环单元测试
└── tools/ToolsTest.java               # 5 tests — 文件工具测试
```

---

## 快速开始

### 环境要求

- Java 17+
- Maven 3.8+

### 构建与运行

```bash
# 构建
mvn clean package -DskipTests

# 设置 API Key（必须）
export OPENAI_API_KEY="sk-your-key-here"

# 运行（流式模式）
java -jar target/greensam-cli-0.0.1-SNAPSHOT.jar

# 运行（非流式模式）
GREENSAM_STREAMING=false java -jar target/greensam-cli-0.0.1-SNAPSHOT.jar
```

### 环境变量

| 变量 | 必须 | 默认值 | 说明 |
|------|------|--------|------|
| `OPENAI_API_KEY` | 是 | - | API 密钥 |
| `OPENAI_BASE_URL` | 否 | `https://api.openai.com/v1` | API 地址（可用于兼容接口） |
| `GREENSAM_MODEL` | 否 | `gpt-4o` | 模型名称 |
| `GREENSAM_SYSTEM_PROMPT` | 否 | 内置提示词 | 系统提示词 |
| `GREENSAM_STREAMING` | 否 | `true` | 是否启用流式输出 |

### REPL 命令

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助 |
| `/clear` | 清空对话历史 |
| `/exit` | 退出程序 |

### 运行测试

```bash
mvn test
```

18 个测试，覆盖序列化、API 客户端、Agent Loop、工具执行。

---

## 技术选型

| 依赖 | 版本 | 用途 |
|------|------|------|
| OkHttp | 4.12.0 | HTTP 客户端，同步和 SSE 流式 |
| Jackson | 2.20.1 | JSON 序列化/反序列化 |
| JLine3 | 3.26.3 | 终端 REPL（历史、补全、ANSI） |
| SLF4J + slf4j-simple | 2.0.16 | 日志 |
| Lombok | 1.18.36 | 减少样板代码 |
| JUnit 5 | 5.10.2 | 单元测试 |
| MockWebServer | 4.12.0 | API Mock 测试 |

---

## 后续完善方向

### 1. 更多工具

| 工具 | 说明 | 难度 |
|------|------|------|
| `WriteFileTool` | 写入/创建文件 | 简单 |
| `GrepTool` | 正则搜索文件内容 | 简单 |
| `BashTool` | 执行 shell 命令 | 中等 |
| `EditTool` | 精确替换文件中的字符串（类似 Claude Code） | 中等 |
| `WebSearchTool` | 联网搜索 | 中等 |

添加新工具只需实现 `Tool` 接口，然后在 `GreensamCli.main()` 中注册：

```java
toolRegistry.register(new WriteFileTool());
```

### 2. 权限系统

在 `ToolRegistry.executeTool()` 前加一层权限检查：

```
LLM 请求执行 write_file("/etc/passwd")
    ↓
权限检查：该路径是否允许写入？
    ↓
┌──────────────┐
│ 提示用户确认   │ ← Allow write_file on /etc/passwd? [y/n]
└──────────────┘
```

参考 Claude Code 的权限模式：自动允许读取，写入/执行需要确认。

### 3. 对话上下文管理

当前对话历史无限增长，会超出 token 限制。需要：

- **Token 计数**：估算当前历史占用多少 token
- **历史截断**：保留系统提示词 + 最近 N 轮对话
- **历史压缩**：用 LLM 摘要旧对话，用摘要替代原文
- **上下文窗口感知**：根据模型 token 上限动态调整

### 4. 多模型支持

当前硬编码 OpenAI 格式。可以抽象出 `Provider` 接口：

```java
public interface Provider {
    ChatResponse send(ChatRequest request);
    // 每家的 tool_call 格式不同，Provider 负责适配
}
```

支持 Anthropic Claude（`tool_use` block）、Google Gemini 等。

### 5. MCP（Model Context Protocol）支持

[MCP](https://modelcontextprotocol.io/) 是 Anthropic 提出的标准化工具协议：

```
greensam-cli ←→ MCP Server ←→ 外部工具
```

实现一个 `McpToolAdapter` 将 MCP 服务器包装为 `Tool` 接口，就能接入任何 MCP 兼容的工具服务器。

### 6. 提示词工程优化

当前系统提示词很简陋。可以增强：

- 项目上下文注入（当前目录结构、git 状态）
- 工具使用示例（few-shot）
- 输出格式指导（Markdown、代码块）
- 角色设定（如"你是一个 Java 开发专家"）

### 7. 构建与分发

- **Maven Shade Plugin**：打包为包含所有依赖的 fat JAR
- **GraalVM Native Image**：编译为原生二进制，秒启动
- **jpackage**：打包为系统原生安装包

### 8. 测试增强

- Agent 端到端测试：MockWebServer 模拟完整的 tool_call 多轮交互
- Streaming 测试：模拟 SSE 事件流
- 覆盖率目标：80%+

---

## 许可

学习项目，仅供学习参考。
