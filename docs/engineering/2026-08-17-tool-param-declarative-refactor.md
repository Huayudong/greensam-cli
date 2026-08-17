# 工具参数声明式改造：record + @Param 统一 Schema 生成与参数绑定

- **日期**：2026-08-17
- **作者**：Macro Ray
- **状态**：已完成，全量测试通过

## 任务范围

原先 7 个工具类的参数 JSON Schema 全部手工拼装 `ObjectNode`（约 150 行样板，骨架代码在 7 处逐字重复），
且 `execute()` 内手工 `arguments.get("xxx")` 解析参数——Schema 与解析两处独立维护，容易漂移。
本次改造引入「record 声明式参数」：一个参数 record + `@Param` 注解同时驱动
Schema 生成与参数绑定，成为参数的单一事实来源。

对模型的 wire 契约（参数名、类型、description、required）保持不变。

## 变更点

### 新增

| 文件 | 职责 |
|------|------|
| `agent/Param.java` | 参数描述注解（`value` 描述 + `required` 必填），标注在 record 组件上 |
| `agent/AbstractTool.java` | 泛型基类：构造时生成并缓存 Schema；`execute()` 统一做参数绑定（`treeToValue` + 必填校验），子类只实现 `doExecute(Args)` |
| `utils/ToolSchemaUtils.java` | Schema 生成工具：反射 `RecordComponent`，类型映射（String→string、Integer/Long→integer、Boolean→boolean、Double→number、枚举→string+enum 取值列表），缺 `@Param` 或不支持的类型构造期即抛异常 |
| `utils/ToolSchemaUtilsTest.java`、`agent/AbstractToolTest.java` | 框架单测（类型映射、命名、必填校验、默认值、容错） |

### 迁移（7 个工具全部改为继承 `AbstractTool`，删除手工 Schema 与手工取参）

`ReadFileTool`、`ListFilesTool`、`WriteFileTool`、`EditFileTool`、`GlobTool`、`GrepTool`、`ExecuteCommandTool`。

- 多词参数名（`old_string`、`new_string`、`replace_all`、`timeout_seconds`、`output_mode`、
  `case_insensitive`、`max_results`）用 `@JsonProperty` 显式标注 snake_case；
  Schema 生成与 Jackson 绑定共用同一份注解，名字必然一致。
- 可选参数默认值收敛到 record 紧凑构造器（如 `timeoutSeconds` 缺省 120、`path` 缺省 `"."`）。
- `GrepTool` 的 `output_mode` 从字符串常量改为枚举 `OutputMode`（wire 名经 `@JsonProperty` 声明），
  Schema 自动带出 `"enum": ["content","files_with_matches","count"]`，非法取值在参数绑定期即报错。
- 顺带清理 `ReadFileTool` 原第 59 行的死代码（创建后丢弃的 `arrayNode()`）。

### 测试补强

- `ToolsTest` 新增 7 个 Schema 形状断言，锁定每个工具对外的参数名 / required 顺序 / 枚举取值，
  防止命名漂移（本次迁移中 `replace_all` 曾漏标 `@JsonProperty` 即被此类断言思路捕获）。
- 既有 34 个工具行为测试**零改动**、全绿，作为迁移不改行为的回归证据。

## 有意的行为变化（均为改善）

1. 必填参数缺失：原先 NPE（`Tool execution failed: null`），现为明确的
   `Tool execution failed: 缺少必填参数: path`（一次性报出全部缺失项）。
2. 参数类型错误 / 非法枚举值：报 `参数解析失败: <Jackson 原因>`，中文前缀。
3. GrepTool Schema 的 `output_mode` 新增 `enum` 取值列表（对模型是增强）。
4. 其余工具业务行为、英文业务错误消息（如 `File not found`）不变。

## 验证结果

- `JAVA_HOME=D:/Java/jdk-21 mvn test`：**75 个测试全部通过，0 失败**
  （含新增 `ToolSchemaUtilsTest` 7 个、`AbstractToolTest` 8 个、Schema 形状断言 7 个）。
- 编译无警告。

## 遗留风险与后续工作

- `ToolSchemaUtils` 类型映射暂不支持 `List<String>` 等复杂类型（构造期抛异常防呆）；
  需要时按同样模式补充 array 映射即可。
- 老工具的错误消息仍是英文（如 `File not found: xxx`）；按 AGENTS.md 约定可后续统一汉化，
  本次不动以缩小变更面。
- Schema description 仍为英文文案（面向模型），保持既有 wire 契约，未改。
