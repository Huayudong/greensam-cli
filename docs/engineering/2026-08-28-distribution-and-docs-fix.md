# 分发与文档修复：fat jar、启动脚本、README 重写与 HTTP 超时加固

- **日期**：2026-08-28
- **作者**：Macro Ray
- **状态**：已完成，89 个测试全部通过（批次①，契约见 `docs/business/roadmap.md` 2.6 节）

## 任务范围

按 roadmap 批次①执行：让「快速开始」从假的变成真的（原 README 的
`java -jar` 因裸 jar 不含依赖必然 `NoClassDefFoundError`），修复 OkHttp 默认
10 秒读超时导致非流式模式慢模型必超时的隐患，重写漂移的 README，
补齐开源配套（CI、`.env.example`、徽章、已知边界声明）。

## 变更点

### 打包与启动

| 文件 | 变更 |
|------|------|
| `pom.xml` | `maven-jar-plugin` 替换为 `maven-shade-plugin`（fat jar）；`ServicesResourceTransformer` 合并 `META-INF/services`（JLine 的 jansi/exec 终端 provider、SLF4J provider 均依赖，不合并会被同名文件覆盖丢失） |
| `bin/greensam-cli.cmd` | 新增 Windows 启动脚本：`chcp 65001` + `-Dfile.encoding/-Dsun.*.encoding/-D*.encoding` 全套 UTF-8 参数，治理 Windows 控制台中文乱码（GBK 代码页是根源） |
| `bin/greensam-cli.sh` | 新增类 Unix 启动脚本（同套编码参数） |

### HTTP 读超时加固（原默认 10s 时序炸弹）

| 文件 | 变更 |
|------|------|
| `config/AppConfig.java` | 新增 `GREENSAM_TIMEOUT_SECONDS` 配置（默认 300），`getIntEnv` 非整数 fail-fast 并指明配置项 |
| `GreensamCli.java` | 共享 `OkHttpClient` 显式配置 read timeout。原默认 10s：流式模式因「字节间超时」语义幸存，非流式模式遇慢模型（推理型常见 30s+）必 `SocketTimeoutException` |
| `test/config/AppConfigTest.java` | 新增 3 测试：默认值 300 / .env 覆盖 / 非整数报错含配置项名 |
| `test/client/OpenAiChatClientTest.java` | 新增 2 测试：`setBodyDelay` 模拟慢响应——超时内成功、超时外抛 `ChatClientException`，锁死读超时配置生效语义 |

### 文档与开源配套

| 文件 | 变更 |
|------|------|
| `README.md` | 重写：修复 5 处漂移（运行命令、slf4j-simple→logback、测试数量改为不写死、工具结构图 2→7 个、Ctrl+C 说法改为「中断当前输入行」）；快速开始改为真实可跑流程；徽章（CI + License）；新增「已知边界」章节诚实标注无审批/无沙箱/黑名单非访问控制/无法中断/历史无上限；「后续方向」收缩为短清单并链接 roadmap |
| `AGENTS.md` | 构建命令占位符 `<build_cmd>` 替换为真实 Maven 命令与 JDK 说明；交付清单增加「用户可见文档同步」检查项 |
| `.env.example` | 英文旧版刷新为中文并补 `GREENSAM_TIMEOUT_SECONDS`。**勘误**：拷问时称「缺 .env.example」不准确——文件实际存在但从未提交（untracked），本次纳入版本控制 |
| `.gitignore` | 增加 `dependency-reduced-pom.xml`（shade 产物） |
| `.github/workflows/ci.yml` | 新增：ubuntu + windows 双平台矩阵跑 `mvn test`（`ExecuteCommandTool` 有 cmd/sh 平台分支，单平台测不出回归） |
| `docs/business/roadmap.md` | 批次①标记完成，更新记录补一行 |

## 验证结果

- `JAVA_HOME=D:/Java/jdk-21 mvn -B test`：**89 个测试全部通过，0 失败**（新增 5 个）。
- `mvn clean package -DskipTests`：BUILD SUCCESS，shade 替换原 jar；
  fat jar 含 3542 个 class，`META-INF/services` 合并完整（jansi/exec provider、SLF4J、Jackson 齐全）。
- 冒烟验证（真实运行 fat jar）：
  - 不设 Key → fail-fast 输出中文配置错误并退出；
  - 设 dummy Key + stdin EOF → 横幅正常渲染、REPL 启动、EOF 干净退出（JLine/logback/Jackson/OkHttp 全部从 fat jar 正常加载）；
  - `bin/greensam-cli.cmd`（CRLF）与 `bin/greensam-cli.sh` 两个脚本同样验证通过，中文无乱码。

## 遗留风险与后续工作

- **CI 绿灯待推送后确认**：工作流已就绪但未运行过；推送后看 Actions 首次结果，
  若 windows 矩阵有平台性失败（如路径分隔符/编码）按报错修正。
- 启动脚本内版本号 `0.0.1-SNAPSHOT` 为硬编码，将来升版本需同步两处脚本。
- `.cmd` 脚本 `chcp 65001` 会改当前控制台会话代码页（脚本进程范围内），属预期行为。
- 徽章指向 GitHub Actions（`Huayudong/greensam-cli`），gitee 侧不显示 CI 状态，属预期。
- 批次②（中断机制）为下一个建议批次，详见 roadmap 第三章。
