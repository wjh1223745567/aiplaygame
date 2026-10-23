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
 * 持有与 mcpserver 的唯一会话（stdio 子进程或 Streamable HTTP），并把业务动作映射到工具调用。
 *
 * <p>传输方式由 {@code AUTOGAME_MCP_URL} 决定：未设置时拉起本地 Python 子进程；容器里则连
 * 宿主机上常驻的 HTTP 端点。{@link GameTools}/{@code McpSyncClient} 是阻塞式的，因此所有调用
 * 串行化；会话首次使用时才建立，避免只跑 Web 时也占着一条连接。
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

    public synchronized Map<String, Object> ping() {
        return tools().ping();
    }

    public synchronized List<Map<String, Object>> windows() {
        return tools().listWindows();
    }

    public synchronized Map<String, Object> status() {
        return tools().gameStatus();
    }

    public synchronized Map<String, Object> login(double timeoutSeconds, boolean autoOpen) {
        return tools().login(timeoutSeconds, autoOpen);
    }

    public synchronized Map<String, Object> close(double timeoutSeconds, boolean force) {
        return tools().closeGame(timeoutSeconds, force);
    }

    public synchronized Map<String, Object> daily(String goal, int maxSteps, boolean dryRun) {
        return tools().dailyTask(goal, maxSteps, dryRun);
    }

    public synchronized Map<String, Object> market(String goal, int maxSteps, boolean dryRun) {
        return tools().marketScan(goal, maxSteps, dryRun);
    }

    /** server 侧工具清单（供 AI 服务生成提示词，避免两边描述漂移）。 */
    public synchronized List<Map<String, Object>> toolSpecs() {
        return tools().toolSpecs();
    }

    /** 通用调度：AI 服务按工具名执行任意工具。 */
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
