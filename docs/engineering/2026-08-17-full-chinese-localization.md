# Agent 全中文环境改造

> 日期：2026-08-17
> 作者：Macro Ray

## 任务范围

让 Agent 支持全中文环境：系统提示词中文化并强制简体中文回复；工具名/参数名保持英文，
`getDescription()` 与 `@Param` 描述改为中文；CLI 界面与工具报错信息全部中文化。

## 变更点

### 1. 系统提示词（AppConfig）

- 默认系统提示词改为中文，并显式要求「始终使用简体中文思考和回复」。
- `GREENSAM_SYSTEM_PROMPT` 环境变量覆盖机制不变。
- 缺失 API Key 等配置报错信息改中文。

### 2. 工具层（7 个工具 + ToolRegistry）

- `getName()` 与参数 wire 名（`old_string`、`output_mode` 等）保持英文——API 契约不动。
- `getDescription()`、`@Param` 描述全部改中文。
- `ToolExecutionException` 报错信息、工具返回值摘要（如「已创建 xxx（N 字节）」「已编辑 xxx（替换 N 处）」）、
  截断标记（「已截断」）改中文。
- wire 标识保留英文：`exit_code=`、`--- stdout ---`、`d`/`f` 类型前缀、`content|files_with_matches|count` 枚举值。
- INFO/WARN 日志改中文（符合 AGENTS.md「日志内容尽量用中文」）。

### 3. CLI 界面

- `Repl`：横幅副标题、/help 帮助、再见/清空/未知命令提示改中文。
- `TerminalRenderer`：输出标签 `[系统]` `[工具]` `[结果]` `[错误]`。
- `AgentLoop`：工具失败包装信息（「工具执行失败:」「错误:」）与循环异常消息改中文。
- `OpenAiChatClient` / `OpenAiStreamingChatClient`：请求失败、非法 URL、API 错误等用户可见异常改中文。

### 4. 测试同步

- `ToolsTest`（12 处）、`AgentLoopTest`（1 处）中依赖英文文案的断言同步更新。

## 验证结果

- `mvn test`：75 个测试全绿，BUILD SUCCESS。
- 测试日志确认工具层 INFO/WARN 日志已输出中文。

## 遗留风险

- 工具描述改为中文主要适配国产模型（GLM/Qwen/DeepSeek）；若后续切换 GPT/Claude，
  工具选择准确度可能有细微下降，可用 `GREENSAM_SYSTEM_PROMPT` 反向要求英文描述场景验证。
- `ReadFileTool` 固定 UTF-8 读取，遇到 GBK 存量文件会乱码（未在本次范围内）。

## 后续工作

- 文件读取 GBK 降级兼容（BOM 探测或 juniversalchardet）。
- Windows cmd/PowerShell 下控制台编码兜底（启动脚本 `chcp 65001` 或 `-Dstdout.encoding=UTF-8`）。
