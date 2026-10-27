package com.autogame.host;

import com.autogame.client.GameTools;
import com.autogame.client.McpClientFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 持有与 mcpserver 的唯一会话（stdio 子进程或 Streamable HTTP），把 HTTP 请求转成一次工具调用。
 *
 * <p>传输方式由 {@code AUTOGAME_MCP_URL} 决定：未设置时拉起本地 Python 子进程；容器里则连
 * 宿主机上常驻的 HTTP 端点。{@link GameTools}/{@code McpSyncClient} 是阻塞式的，因此所有调用
 * 串行化；会话首次使用时才建立，避免只跑 Web 时也占着一条连接。
 *
 * <p>这里不做任何决策：识别类工具的参数与结果都原样进出，host 只是门面。
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private McpClientFactory factory;

    private synchronized GameTools tools() {
        if (factory == null) {
            String url = McpClientFactory.configuredUrl();
            log.info(url == null ? "拉起 mcpserver（stdio 子进程）..." : "连接 mcpserver（{}）...", url);
            factory = McpClientFactory.start();
            log.info("mcpserver 已就绪");
        }
        return factory.tools();
    }

    /** server 侧工具清单（name / description / 参数 schema），客户端据此渲染调用台。 */
    public synchronized List<Map<String, Object>> toolSpecs() {
        return tools().toolSpecs();
    }

    /** 通用调度：按工具名执行任意工具，参数与结果原样透传。 */
    public synchronized Object invoke(String tool, Map<String, Object> args) {
        return tools().invoke(tool, args);
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (factory != null) {
            factory.close();
            factory = null;
        }
    }
}
