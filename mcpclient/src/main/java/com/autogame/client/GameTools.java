package com.autogame.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具封装：清单 + 通用调用。
 *
 * <p>server 侧只有只读识别工具（ping / list_windows / capture / ocr / layout / detect），
 * 所以这里不再有类型化的业务方法：参数与结果都原样进出，由调用方自己解释。
 *
 * <p>所有方法返回 server 的结构化结果（{@code structuredContent}）；若 server 只返回
 * JSON 文本，则解析后归一化成 Map/List；非 JSON 文本原样返回字符串。工具执行失败
 * （{@code isError=true}）统一抛 {@link ToolCallException}。
 */
public final class GameTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpSyncClient client;

    GameTools(McpSyncClient client) {
        this.client = client;
    }

    /**
     * 列出 server 侧全部工具（name / description / 参数 schema / 必填项）。
     *
     * <p>客户端的工具下拉框和 {@code /ai/tools} 都读这一份，Python 侧加工具时无需改 Java。
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
            spec.put("description", tool.description() == null ? "" : tool.description());
            spec.put("parameters", schema == null || schema.properties() == null
                    ? Map.of() : schema.properties());
            spec.put("required", schema == null || schema.required() == null
                    ? List.of() : schema.required());
            specs.add(spec);
        }
        return specs;
    }

    /** 通用入口：按工具名 + 参数直接执行，返回归一化后的结构化结果。 */
    public Object invoke(String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
                .name(toolName)
                .arguments(arguments == null ? Map.of() : arguments)
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

    /** 工具执行失败（server 端返回 isError）。 */
    public static final class ToolCallException extends RuntimeException {
        ToolCallException(String toolName, String message) {
            super("工具 " + toolName + " 执行失败: " + message);
        }
    }
}
