package com.autogame.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 以 stdio 拉起 Python MCP server，并完成 initialize 握手。
 *
 * <p>server 进程 = 项目内 <code>mcpserver/.venv/bin/python -m game_mcp.server</code>，
 * 工作目录固定为 <code>mcpserver/</code>，因此不需要额外的 PYTHONPATH。
 * 项目根按以下顺序解析：环境变量 {@code AUTOGAME_ROOT} → 由 user.dir 向上查找
 * 含 {@code mcpserver/config.json} 与 {@code mcpserver/game_mcp} 的目录。
 */
public final class McpClientFactory implements AutoCloseable {

    private static final String PYTHON_REL = "mcpserver/.venv/bin/python";
    private static final String SERVER_MODULE = "game_mcp.server";

    private final McpSyncClient client;
    private final GameTools tools;

    private McpClientFactory(McpSyncClient client) {
        this.client = client;
        this.tools = new GameTools(client);
    }

    /** 拉起 server 并完成握手。 */
    public static McpClientFactory start() {
        return start(resolveProjectRoot());
    }

    public static McpClientFactory start(Path projectRoot) {
        Path python = projectRoot.resolve(PYTHON_REL);
        if (!Files.isExecutable(python)) {
            throw new IllegalStateException(
                    "找不到 MCP server 的 Python 解释器: " + python + "\n请先执行 bash scripts/bootstrap.sh");
        }
        Path serverDir = projectRoot.resolve("mcpserver");

        ServerParameters params = ServerParameters.builder(python.toString())
                .args("-m", SERVER_MODULE)
                .addEnvVar("PYTHONPATH", serverDir.toString())
                .addEnvVar("PYTHONUNBUFFERED", "1")
                .build();

        // 覆写 getProcessBuilder 以固定工作目录：SDK 的 ServerParameters 不支持 cwd
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper()) {
            @Override
            protected ProcessBuilder getProcessBuilder() {
                return new ProcessBuilder().directory(serverDir.toFile());
            }
        };

        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMinutes(10))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
        return new McpClientFactory(client);
    }

    public GameTools tools() {
        return tools;
    }

    public McpSyncClient client() {
        return client;
    }

    /** 项目根：AUTOGAME_ROOT 优先，其次从 user.dir 向上找标志文件。 */
    public static Path resolveProjectRoot() {
        String fromEnv = System.getenv("AUTOGAME_ROOT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv).toAbsolutePath().normalize();
            if (Files.isDirectory(p.resolve("mcpserver/game_mcp"))) {
                return p;
            }
        }
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("mcpserver/game_mcp"))
                    && Files.exists(p.resolve("mcpserver/config.json"))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "无法定位项目根（缺少 mcpserver/config.json 或 mcpserver/game_mcp），请设置 AUTOGAME_ROOT");
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (RuntimeException e) {
            client.close();
        }
    }
}
