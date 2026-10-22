package com.autogame.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具的 Java 薄封装：只保留命令行与状态查询真正调用的类型化方法
 * （ping / list_windows / game_status / login / daily_task / market_scan），
 * 其余工具一律经 {@link #invoke(String, Map)} 按名字调用。
 *
 * <p>所有方法都返回 server 的结构化结果（{@code structuredContent}）；若 server 只返回
 * JSON 文本，则解析后归一化成 Map/List；非 JSON 文本则归一化成 {@code {"result": ...}}。
 * 工具执行失败（{@code isError=true}）统一抛 {@link ToolCallException}。
 */
public final class GameTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpSyncClient client;

    GameTools(McpSyncClient client) {
        this.client = client;
    }

    // ---------- 基础 ----------

    public Map<String, Object> ping() {
        return call("ping", Map.of());
    }

    public List<Map<String, Object>> listWindows() {
        // server 侧返回 List[dict]，MCP 会把非对象结果包成 {"result": [...]}
        Object result = callRaw("list_windows", Map.of());
        if (result instanceof Map<?, ?> map && map.get("result") instanceof List<?> wrapped) {
            result = wrapped;
        }
        List<Map<String, Object>> windows = new ArrayList<>();
        if (result instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    windows.add(toStringMap(map));
                }
            }
        }
        return windows;
    }

    // ---------- 业务 ----------

    /** 检测游戏是否已打开（running / window_visible / pids）。 */
    public Map<String, Object> gameStatus() {
        return call("game_status", Map.of());
    }

    /** 登录：server 侧先自动检测游戏是否打开，未打开时按 autoOpen 决定是否自动启动。 */
    public Map<String, Object> login(double timeoutSeconds, boolean autoOpen) {
        return call("login", Map.of("timeout", timeoutSeconds, "auto_open", autoOpen));
    }

    public Map<String, Object> dailyTask(String goal, int maxSteps, boolean dryRun) {
        return call("daily_task", Map.of("goal", nullToEmpty(goal), "max_steps", maxSteps, "dry_run", dryRun));
    }

    public Map<String, Object> marketScan(String goal, int maxSteps, boolean dryRun) {
        return call("market_scan", Map.of("goal", nullToEmpty(goal), "max_steps", maxSteps, "dry_run", dryRun));
    }

    // ---------- 工具元信息 ----------

    /**
     * 列出 server 侧全部工具（name / description / 参数 schema）。
     *
     * <p>host 的 AI 服务用它实时生成提示词里的工具清单，因此 Python 侧新增工具时
     * 无需改动 Java，避免两边工具描述漂移。
     */
    public List<Map<String, Object>> toolSpecs() {
        McpSchema.ListToolsResult result = client.listTools();
        List<Map<String, Object>> specs = new ArrayList<>();
        if (result == null || result.tools() == null) {
            return specs;
        }
        for (McpSchema.Tool tool : result.tools()) {
            McpSchema.JsonSchema schema = tool.inputSchema();
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("name", tool.name());
            spec.put("description", nullToEmpty(tool.description()));
            spec.put("parameters", schema == null || schema.properties() == null
                    ? Map.of() : schema.properties());
            spec.put("required", schema == null || schema.required() == null
                    ? List.of() : schema.required());
            specs.add(spec);
        }
        return specs;
    }

    // ---------- 调用与归一化 ----------

    /** 通用入口：按工具名 + 参数直接执行，返回归一化后的结构化结果（供 AI 编排）。 */
    public Object invoke(String toolName, Map<String, Object> arguments) {
        return callRaw(toolName, arguments == null ? Map.of() : arguments);
    }

    /** 通用入口：返回结构化结果。 */
    public Map<String, Object> call(String toolName, Map<String, Object> arguments) {
        Object structured = callRaw(toolName, arguments);
        if (structured instanceof Map<?, ?> map) {
            return toStringMap(map);
        }
        return Map.of("result", structured);
    }

    /** 通用入口：原样返回 structuredContent（可能是 Map 或 List）。 */
    public Object callRaw(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(arguments)
                .build();
        McpSchema.CallToolResult result = client.callTool(request);
        if (Boolean.TRUE.equals(result.isError())) {
            throw new ToolCallException(toolName, textOf(result));
        }
        Object structured = result.structuredContent();
        // server 返回非对象结果时会被包成 {"result": ...}，这里统一拆掉
        if (structured instanceof Map<?, ?> map && map.size() == 1 && map.containsKey("result")) {
            structured = map.get("result");
        }
        if (structured instanceof String text) {
            return parseIfJson(text);
        }
        if (structured != null) {
            return structured;
        }
        // 无 structuredContent：文本可能是 JSON（dict 结果），解析后返回对象
        return parseIfJson(textOf(result));
    }

    private static String textOf(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /** 文本看起来是 JSON 对象/数组时解析成 Map/List，否则原样返回。 */
    private static Object parseIfJson(String text) {
        String s = text == null ? "" : text.trim();
        if (!(s.startsWith("{") || s.startsWith("["))) {
            return text;
        }
        try {
            return JSON.readValue(s, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    private static Map<String, Object> toStringMap(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 工具执行失败（server 端返回 isError）。 */
    public static final class ToolCallException extends RuntimeException {
        private final String toolName;

        ToolCallException(String toolName, String message) {
            super("工具 " + toolName + " 执行失败: " + message);
            this.toolName = toolName;
        }

        public String toolName() {
            return toolName;
        }
    }
}
