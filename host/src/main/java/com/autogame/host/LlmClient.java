package com.autogame.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI 兼容的 {@code /chat/completions} 客户端（host 即 AI 服务）。
 *
 * <p>只依赖 JDK 的 HttpClient，不引入额外 SDK；messages 里可以混入
 * <code>{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}</code>
 * 走视觉模型。模型/端点/超时全部来自 {@link AiSettings}。
 *
 * <p>免费模型会被限流（智谱 429 / code 1305「该模型当前访问量过大」），所以按
 * {@code llm.model} + {@code llm.fallback_models} 依次降级；被限的主模型冷却一段时间，
 * 期间直接用备用模型，不再每轮先撞一次 429 白等几秒。
 */
@Component
public class LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** 这些状态说明「这个模型暂时不行」，换一个往往能成；400/401 换模型也没用。 */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 429, 500, 502, 503, 504);

    /** 主模型被限流后的冷却时长：免费模型的排队风暴一般几分钟才散。 */
    private static final long PRIMARY_COOLDOWN_SECONDS = 120;

    private final AiSettings settings;
    private final HttpClient http;

    /** 主模型冷却到什么时候（毫秒时间戳），期间把它排到最后再试。 */
    private volatile long primaryRetryAt;
    /** 上次告警过的降级目标，避免每轮都刷同一条。 */
    private volatile String warnedFallback;
    /** 最近一次成功调用的模型：可能不是 llm.model（限流时降级到备用）。 */
    private volatile String modelUsed;

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
        IOException last = null;
        for (String model : order()) {
            try {
                String text = ask(model, messages);
                if (!model.equals(settings.model())) {
                    if (!model.equals(warnedFallback)) {
                        warnedFallback = model;
                        log.warn("主模型 {} 不可用，已降级到 {}（{}s 后再试主模型）",
                                settings.model(), model, PRIMARY_COOLDOWN_SECONDS);
                    }
                    primaryRetryAt = System.currentTimeMillis() + PRIMARY_COOLDOWN_SECONDS * 1000L;
                } else {
                    warnedFallback = null;
                }
                modelUsed = model;
                return cleanOutput(text);
            } catch (RetryableException e) {
                last = e;
                log.warn("模型 {} 调用失败，换下一个: {}", model, e.getMessage());
            }
        }
        throw last != null ? last : new IOException("没有可用模型（检查 llm.model / llm.fallback_models）");
    }

    /**
     * 本轮尝试顺序。主模型在冷却期内就排到最后：限流时每轮先撞一次 429 只是白白多等几秒。
     */
    private List<String> order() {
        List<String> models = new ArrayList<>(settings.models());
        if (models.size() > 1 && System.currentTimeMillis() < primaryRetryAt) {
            models.add(models.remove(0));
        }
        return models;
    }

    /** 最近一次成功调用的模型，供决策日志记录真实值。 */
    public String modelUsed() {
        return modelUsed != null ? modelUsed : settings.model();
    }

    /** 用指定模型发一次请求。 */
    private String ask(String model, List<Map<String, Object>> messages)
            throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
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

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new RetryableException("超时 " + (long) settings.timeoutSeconds() + "s");
        } catch (IOException e) {
            throw new RetryableException("网络异常: " + e.getMessage());
        }
        if (response.statusCode() >= 300) {
            String body = truncate(response.body(), 300);
            if (RETRYABLE_STATUS.contains(response.statusCode())) {
                throw new RetryableException("HTTP " + response.statusCode() + ": " + body);
            }
            throw new IOException("HTTP " + response.statusCode() + ": " + body);
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

    /**
     * 剥掉推理模型的包装：glm-4.1v-thinking-flash 返回
     * <code>&lt;think&gt;…&lt;/think&gt;&lt;answer&gt;&lt;|begin_of_box|&gt;{…}&lt;|end_of_box|&gt;&lt;/answer&gt;</code>，
     * 原样交给上层解 JSON 会失败。普通模型的 ```json 代码块不动，那层由调用方处理。
     */
    static String cleanOutput(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        int start;
        while ((start = s.indexOf("<think")) >= 0) {
            int end = s.indexOf("</think", start);
            if (end < 0) {
                // 没闭合 = 输出被截断或只吐了思考：思考里也夹着花括号，拿它当决策会点错东西，
                // 宁可当这次失败（上层会降级/报错）
                s = s.substring(0, start);
                break;
            }
            int close = s.indexOf('>', end);
            s = s.substring(0, start) + s.substring(close < 0 ? end + 7 : close + 1);
        }
        return s.replace("<answer>", "").replace("</answer>", "")
                .replace("<|begin_of_box|>", "").replace("<|end_of_box|>", "")
                .trim();
    }

    /** 换个模型就可能成功的失败（限流 / 超时 / 网络 / 服务端错误）。 */
    private static final class RetryableException extends IOException {
        RetryableException(String message) {
            super(message);
        }
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…(已截断)";
    }
}
