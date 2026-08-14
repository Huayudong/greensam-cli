package com.greensamcli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greensamcli.agent.AgentLoop;
import com.greensamcli.agent.ToolRegistry;
import com.greensamcli.cli.CliRenderer;
import com.greensamcli.cli.Repl;
import com.greensamcli.cli.TerminalRenderer;
import com.greensamcli.client.ChatClient;
import com.greensamcli.client.OpenAiChatClient;
import com.greensamcli.client.OpenAiStreamingChatClient;
import com.greensamcli.config.AppConfig;
import com.greensamcli.tools.EditFileTool;
import com.greensamcli.tools.ExecuteCommandTool;
import com.greensamcli.tools.GlobTool;
import com.greensamcli.tools.GrepTool;
import com.greensamcli.tools.ListFilesTool;
import com.greensamcli.tools.ReadFileTool;
import com.greensamcli.tools.WriteFileTool;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

/**
 * greensam-cli 的主入口类——组装所有组件并启动 REPL。
 *
 * <p>这个类是整个应用的"接线板"，职责是创建各个组件实例并把它们连接起来：</p>
 *
 * <pre>
 * AppConfig           ← 加载环境变量配置
 *     │
 * OkHttpClient        ← HTTP 客户端
 * ObjectMapper        ← JSON 序列化/反序列化
 *     │
 * ├── OpenAiChatClient          ← 同步 API 客户端
 * ├── OpenAiStreamingChatClient ← 流式 API 客户端
 *     │
 * ToolRegistry        ← 注册可用工具（read/list/write/edit/grep/glob/execute_command）
 *     │
 * AgentLoop           ← 核心推理引擎（注入 client + registry）
 *     │
 * TerminalRenderer    ← 终端输出渲染
 *     │
 * Repl                ← 终端交互循环（注入 agentLoop + renderer）
 *     │
 * repl.run()          ← 启动主循环
 * </pre>
 */
@Slf4j
public class GreensamCli {

    public static void main(String[] args) {
        try {
            // ① 加载配置（环境变量）
            AppConfig config = new AppConfig();

            // ② 创建基础设施
            OkHttpClient httpClient = new OkHttpClient();
            ObjectMapper objectMapper = new ObjectMapper();

            // ③ 创建 API 客户端（同步 + 流式）
            ChatClient chatClient = new OpenAiChatClient(
                    httpClient,
                    objectMapper,
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModel()
            );

            OpenAiStreamingChatClient streamingClient = new OpenAiStreamingChatClient(
                    httpClient,
                    objectMapper,
                    config.getApiKey(),
                    config.getBaseUrl(),
                    config.getModel()
            );

            // ④ 注册工具
            ToolRegistry toolRegistry = new ToolRegistry();
            // 文件读写类
            toolRegistry.register(new ReadFileTool());
            toolRegistry.register(new ListFilesTool());
            toolRegistry.register(new WriteFileTool());
            toolRegistry.register(new EditFileTool());
            // 检索导航类
            toolRegistry.register(new GrepTool());
            toolRegistry.register(new GlobTool());
            // 命令执行类
            toolRegistry.register(new ExecuteCommandTool());
            // 未来添加新工具只需在这里 register 即可

            // ⑤ 创建 AgentLoop（注入 API 客户端和工具注册表）
            boolean useStreaming = config.isStreaming();

            AgentLoop agentLoop = new AgentLoop(
                    chatClient, streamingClient,
                    toolRegistry, objectMapper, config.getSystemPrompt()
            );

            // ⑥ 创建终端渲染器和 REPL
            CliRenderer renderer = new TerminalRenderer();
            Repl repl = new Repl(agentLoop, renderer, useStreaming);

            // ⑦ 启动 REPL 主循环
            repl.run();

        } catch (IllegalStateException e) {
            // 配置错误（缺少 API Key 等）
            log.error("Configuration error: {}", e.getMessage());
            log.error("Please set the OPENAI_API_KEY environment variable or create a .env file.");
            System.exit(1);
        } catch (Exception e) {
            log.error("Fatal error", e);
            System.exit(1);
        }
    }
}
