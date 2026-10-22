package com.autogame.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容的 {@code /chat/completions} 客户端（host 即 AI 服务）。
 *
 * <p>只依赖 JDK 的 HttpClient，不引入额外 SDK；messages 里可以混入
 * <code>{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}</code>
 * 走视觉模型。模型/端点/超时全部来自 {@link AiSettings}。
 */
@Component
public class LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiSettings settings;
    private final HttpClient http;

    public LlmClient(AiSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 发一轮对话，返回模型输出的纯文本（可能是一段 JSON）。 */
    public String chat(List<Map<String, Object>> messages) throws IOException, InterruptedException {
        if (!settings.ready()) {
            throw new IOException("未配置 llm.api_key（host/config.json 的 llm.api_key 或环境变量 LLM_API_KEY）");
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("model", settings.model());
        payload.put("messages", messages);
        payload.put("temperature", settings.temperature());
        payload.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.apiBase() + "/chat/completions"))
                .timeout(Duration.ofSeconds(Math.max(5, (long) settings.timeoutSeconds())))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 400));
        }
        JsonNode root = JSON.readTree(response.body());
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IOException("返回结构异常: " + truncate(response.body(), 400));
        }
        if (content.isTextual()) {
            return content.asText();
        }
        // 少数网关把 content 返回成分段数组
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part.path("type").asText("").equals("text")) {
                sb.append(part.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(已截断)";
    }
}
