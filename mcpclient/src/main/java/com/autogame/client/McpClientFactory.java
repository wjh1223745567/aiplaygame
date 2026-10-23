package com.autogame.client;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 建立到 mcpserver 的 MCP 会话，传输方式由环境决定：
 * <ul>
 *   <li>默认（stdio）：拉起 {@code mcpserver/.venv/bin/python -m game_mcp.server} 子进程，
 *       工作目录固定为 {@code mcpserver/}，因此不需要额外的 PYTHONPATH。</li>
 *   <li>{@value #ENV_URL} 非空（容器化部署）：连已常驻的 Streamable HTTP 端点，例如
 *       {@code http://host.docker.internal:18090/mcp}。此模式不找 Python、也不用项目根，
 *       因为 mcpserver 必须操作 macOS 本机窗口，只能跑在宿主机上。</li>
 * </ul>
 *
 * <p>stdio 模式下项目根按以下顺序解析：环境变量 {@code AUTOGAME_ROOT} → 由 user.dir 向上
 * 查找含 {@code mcpserver/config.json} 与 {@code mcpserver/game_mcp} 的目录。
 */
public final class McpClientFactory implements AutoCloseable {

    /** 设置后走 Streamable HTTP（完整的 MCP 端点 URL，含 /mcp 路径）。 */
    public static final String ENV_URL = "AUTOGAME_MCP_URL";

    private static final Logger log = LoggerFactory.getLogger(McpClientFactory.class);
    private static final String PYTHON_REL = "mcpserver/.venv/bin/python";
    private static final String SERVER_MODULE = "game_mcp.server";

    private final McpSyncClient client;
    private final GameTools tools;

    private McpClientFactory(McpSyncClient client) {
        this.client = client;
        this.tools = new GameTools(client);
    }

    /** 按环境变量选传输：AUTOGAME_MCP_URL 有值连 HTTP，否则拉起 stdio 子进程。 */
    public static McpClientFactory start() {
        String url = configuredUrl();
        if (url != null) {
            return startHttp(url);
        }
        return start(resolveProjectRoot());
    }

    /** 已配置的 HTTP 端点，未配置返回 null。 */
    public static String configuredUrl() {
        String url = System.getenv(ENV_URL);
        return url == null || url.isBlank() ? null : url.trim();
    }

    /** 连接常驻的 Streamable HTTP server（host 跑在容器里时的唯一选择）。 */
    public static McpClientFactory startHttp(String url) {
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(url)
                .jsonMapper(McpJsonDefaults.getMapper())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        return connect(transport, "streamable-http " + url);
    }

    /** 以子进程拉起 stdio server 并完成握手。 */
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
        return connect(transport, "stdio " + python);
    }

    private static McpClientFactory connect(McpClientTransport transport, String desc) {
        log.info("连接 mcpserver（{}）", desc);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofMinutes(10))
                .initializationTimeout(Duration.ofSeconds(60))
                .build();
        client.initialize();
        log.info("mcpserver 已就绪（{}）", desc);
        return new McpClientFactory(client);
    }

    public GameTools tools() {
        return tools;
    }

    public McpSyncClient client() {
        return client;
    }

    /** 项目根：AUTOGAME_ROOT 优先（容器里可能只挂载了 config/debug，不再要求带 Python 源码），
     *  其次从 user.dir 向上找标志文件。 */
    public static Path resolveProjectRoot() {
        String fromEnv = System.getenv("AUTOGAME_ROOT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            Path p = Path.of(fromEnv).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
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
