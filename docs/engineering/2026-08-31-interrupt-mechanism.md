# 批次②：中断机制

- **日期**：2026-08-31
- **任务范围**：路线图批次②（契约见 `docs/business/roadmap.md` 2.2 / 2.3 节）——
  执行中 Ctrl+C 中断当前回合（含子进程强杀）、中断后对话可继续、
  AgentLoop 扩展点（cancel API + 工具执行前后 hook）一次性设计到位。

## 一、变更点

### 1.1 AgentLoop：cancel API 与取消安全点

- 新增 `cancel()` 公开 API：置位 `cancelRequested`（AtomicBoolean）并打断回合线程
  （`roundThread` volatile 记录）；**空闲（无进行中的回合）时为无操作**，幂等、可跨线程调用。
- 取消语义只认标志位，`interrupt` 仅作为唤醒阻塞操作的手段。安全点三处：
  1. 每次循环迭代顶部（发送 LLM 前）；
  2. LLM 响应返回后（整条 assistant 消息丢弃、不入历史——含 tool_calls 的消息若无
     对应结果会让对话结构非法）；
  3. 执行每个工具前（见 1.3）。
- 取消以 `AgentCancelledException`（新增）终结回合，回合结束在 `finally` 中
  `endRound()`：清空回合线程记录并**清掉遗留的中断标志**，避免污染主线程后续的
  阻塞操作（JLine 读输入）。
- `executeTools` 检测到取消时，把剩余未执行的 tool_call 全部补上
  「用户已中断本轮执行，该工具调用未执行。」占位结果再返回（`fillInterruptedToolResults`）。
  OpenAI 协议要求 assistant 的每个 tool_call 必须有一条对应 tool result，
  补齐后历史才是合法对话，用户可直接问「刚才做到哪了」。

### 1.2 流式读取工作线程化（取消即时性的关键）

- 现状问题：`sendStreaming` 在回合线程上同步阻塞读 SSE，而普通 socket 读**不响应
  线程中断**——直接读的话，取消只能等模型生成完整个响应，提示符迟迟不返回。
- 改造：SSE 读取挪到**独立守护线程**（`llm-stream-reader`），回合线程阻塞在
  latch 上（可被 interrupt 立刻打断）。取消后残留的读取线程自行读到流结束自然退出，
  其迟到的增量按**每次迭代独立的 `discarded` 标志**丢弃——不能用全局 cancelRequested
  做守卫：取消后回合立刻结束、下一回合会把全局标志复位，届时残留线程若仍看全局标志，
  迟到增量会打进新一轮的提示符。

### 1.3 ExecuteCommandTool：进程树强杀 + 中断/超时区分

- 新增 `killProcessTree`：先快照 `process.descendants()` 逐个 `destroyForcibly`，再杀直接子进程。
  Windows 下命令经两层 `cmd /c` 封装，只杀直接子进程会留下孤儿孙进程继续占住输出管道。
  超时路径同样升级为树杀（原先只杀直接子进程，是存量隐患）。
- `waitFor` 返回 false 有两种原因（超时 / 被打断）：先 `Thread.interrupted()` 取走中断标志，
  树杀与收尾 join 后再判定——被打断抛 `ToolExecutionException("命令因用户中断被强制终止")`
  （由 AgentLoop 作为工具结果回传 LLM），并恢复中断标志保持打断语义向上传递；
  未被打断才是超时，返回原有的 `formatTimeout` 结果。

### 1.4 Repl：信号接线（关键踩坑见第二节）

- `Signal.handle(new Signal("INT"), signal -> agentLoop.cancel())`（JVM 级 sun.misc 信号钩子）。
- 状态互补：空闲读输入时终端处于 raw 模式，Ctrl+C 是普通按键，由 JLine 接管
  （`UserInterruptException`，只清输入行），**不生成信号**；回合执行期间终端处于
  常规模式，Ctrl+C 产生真实中断信号送达处理器。两种状态天然互不干扰。
- 取消路径的渲染：`runBlocking` / `runStreaming` 捕获 `AgentCancelledException`，
  关闭可能开着的流式块后提示「⏹ 已中断当前回合」，清空待渲染用量，回到提示符。

### 1.5 批次④扩展点铺设（本批只加结构、不加实现）

- 新增 `ToolExecutionHook` 接口（`beforeExecute` 返回非 null 即拦截 + `afterExecute` 通知），
  `ToolRegistry` 持有钩子列表并在 `executeTool` 前后依次回调。批次④的交互式审批
  以此接口落地，无需再改核心类。

## 二、踩坑记录：Windows 控制台 Ctrl+C 的验证之路

本次最曲折的部分不是实现，而是「如何证明信号链路真的通」。逐条记录：

1. **`Terminal.handle(Signal.INT, ...)` 在 Windows jansi 终端上不可靠（已废弃 provider）**。
   首版接线用 JLine 的信号机制，真实控制台实测信号不到达（日志无任何取消记录）。
   改用 JVM 级 `sun.misc.Signal`（HotSpot 自己注册了控制台 CTRL 处理器，POSIX 上
   JLine 底层同样是它），这与实现无关、纯接线层替换。
2. **`GenerateConsoleCtrlEvent` 在 Win11 + Windows Terminal（ConPTY 伪控制台）下不投递**。
   自动化验证首选方案：PowerShell/ctypes `AttachConsole(pid)` +
   `GenerateConsoleCtrlEvent(CTRL_C_EVENT, 0)`——Win32 调用全部返回成功，但对
   **纯 JVM（无任何 JLine）**的进程做对照实验：处理器不触发、进程也不死，
   证明事件根本没有送进目标控制台会话。经典 conhost 窗口下同样不投递。
   该路径在本机不可用于验证（`scripts/send_ctrlc.py` 已删除，避免留下不可用的工具）。
3. **WScript.Shell `SendKeys('^c')` 注入 Windows Terminal 同样无效**：AppActivate 成功、
   按键发出，但纯 JVM 对照依旧无反应（推测被 WT 自身的 Ctrl+C 绑定/输入管线吞掉）。
4. **winpty 的 `WriteConsoleInput` 路径有效**——这是与物理键盘等效的真实控制台输入。
   决定性实验（`TestConsoleSignal`，jline 变体：先创建 JLine system 终端再注册
   sun.misc 处理器）：管道写入 `\x03` 后日志记录
   `[INT] sun.misc handler fired: SIGINT`——**JLine 存在不影响 JVM 级信号处理器的触发**，
   产品接线方式成立。
5. **winpty 代理自身的缺陷阻止了全场景脚本化验证**：非 TTY 模式下其控制台尺寸为 0，
   CLI 的富输出（ANSI 擦除/流式重绘）会触发 winpty agent 断言崩溃
   （`winpty.cc:924, cols > 0 && rows > 0`），代理一死控制台即毁。winpty 已废弃，
   不再深究；三幕场景的完整像素级验证改由「等价链路证明 + 单测」组合覆盖（见第三节）。

## 三、验证结果

| 验证项 | 方式 | 结果 |
|--------|------|------|
| 全量回归 | `mvn test` | **127 全绿**（原 115 + 新增 12） |
| cancel() 全逻辑（安全点/响应丢弃/占位补齐/续话/流式即时取消/迟到事件丢弃） | `AgentLoopCancelTest` 5 用例（假 client + 真实多线程，无终端） | 通过 |
| ToolExecutionHook 扩展点（放行/拦截/失败通知/无钩子行为不变） | `ToolRegistryTest` 4 用例 | 通过 |
| 命令工具用户中断（真实 ping 进程 + 真实线程打断，<4s 返回且抛中断异常） | `ToolsTest` 新增用例 | 通过 |
| 进程树强杀（Windows 两层 cmd 派生的孙进程 ping.exe 无残留，tasklist 探测；超时与中断两条路径） | `ToolsTest` 新增 2 用例（`@EnabledOnOs(WINDOWS)`） | 通过 |
| 真实控制台 CTRL_C → JVM 级处理器（JLine 终端在场） | winpty `WriteConsoleInput` 注入 `\x03` 的对照实验 | `[INT] sun.misc handler fired: SIGINT` |
| 真实终端整体回归 | 新 fat jar 在 Windows Terminal 真实控制台跑完整回合（首轮接线版本跑过 20 轮工具循环，最终版跑正常回合） | 渲染 / 工具执行 / 退出无回归 |
| 「⏹ 已中断当前回合」提示的实屏像素 | 未能在自动化中捕获（见第二节 5） | 逻辑由单测覆盖（渲染走既有 `displaySystem` 通路），**建议手工 10 秒复核**：真实终端跑 CLI → 让模型执行长命令 → 按 Ctrl+C |

## 四、遗留风险

1. **非流式模式下中断有延迟**：`client.send` 的 HTTP 读取不可中断，Ctrl+C 只置标志，
   要等响应返回后在安全点丢弃。默认流式模式不受影响。已写入 README「已知边界」。
2. **取消后的流式连接自然耗尽**：残留读取线程与 SSE 连接保持到服务端流结束
   （增量已被丢弃），服务端会照常计费这部分生成。属教学项目可接受的取舍。
3. **三幕场景（工具中断 → 续问 → 流式取消）未在真实终端做像素级串联验证**：
   因 winpty 代理缺陷（第二节 5）无法脚本化，等价链路已分段证明；
   若后续需要可手工执行，或等 CI 引入真实 TTY 的方案。
4. `sun.misc.Signal` 属 `jdk.unsupported` 内部 API（Netty 等主流库同款用法），
   若未来 JDK 移除需迁移（预计会有正式替代）。

## 五、后续工作

- 批次③（上下文截断，独立可插队）与批次④（审批层）均已解锁；
  批次④直接在本次铺设的 `ToolExecutionHook` 上加实现即可。
- 验证用 mock LLM 端点保留在 `scripts/mock_llm_server.py`
  （三幕脚本化行为，`python scripts/mock_llm_server.py [端口]`），供手工验证复用。
