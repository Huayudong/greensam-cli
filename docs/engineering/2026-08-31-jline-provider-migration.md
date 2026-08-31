# JLine 终端 provider 迁移：jansi → jna 并关闭废弃提示

- **日期**：2026-08-31
- **现象**：真实终端启动 CLI 时输出
  `警告: The terminal provider jansi has been deprecated...`
- **结论**：功能无影响，属 JLine 的 provider 废弃提示；本次迁移 provider 并按官方
  文档关闭提示，启动输出恢复干净。

## 背景：JLine 的 provider 废弃政策

JLine 3.24 起把全部本地终端 provider（**jansi / jna / exec**）陆续标记废弃，
官方唯一推荐方向是 `ffm` provider——基于 Java 22 定稿的 Foreign Function & Memory API。

本项目 target Java 17，ffm 不可用，即 **Java 17 下不存在无废弃标记的本地 provider**。
故处理方式为两层：

1. 依赖从 `jline-terminal-jansi` 换成 `jline-terminal-jna`（迁移过程中实测发现
   3.26 里 jna 同样在废弃名单，但它是 17 上唯一仍在维护的本地后端）；
2. 按 JLine 文档提供的方式显式关闭提示：`Repl.run()` 创建终端前设置系统属性
   `org.jline.terminal.disableDeprecatedProviderWarning=true`
   （放在代码里而非启动脚本，`java -jar` 直启同样生效）。

未来若项目升到 Java 22+，可追加 `jline-terminal-ffm` 依赖（MR-jar 设计，
低版本 JDK 会忽略其中的高版本类），届时可移除 jna 与该属性。

## 变更点

- `pom.xml`：`jline-terminal-jansi` → `jline-terminal-jna`（3.26.3，JNA 传递依赖自动带入）；
- `Repl.java`：终端创建前关闭废弃提示；两处注释中的 provider 表述同步；
- 注：批次②的 Ctrl+C 接线用 JVM 级 `sun.misc.Signal`，与 provider 无关，迁移不受影响。

## 验证结果

- fat jar 内 jansi 类 0 个、jna provider 与 JNA 库（`com/sun/jna`）齐全；
- winpty 真实控制台冒烟：启动输出**零警告**，banner 正常，`/exit` 干净退出；
- `mvn test` 127 全绿。
