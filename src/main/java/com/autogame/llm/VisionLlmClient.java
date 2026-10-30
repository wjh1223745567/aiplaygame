package com.autogame.llm;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.autogame.config.AutoGameProperties;
import com.autogame.support.ToolException;

/**
 * 免费视觉大模型客户端，走 OpenAI 兼容协议（默认智谱 GLM-4V-Flash）。
 *
 * <p>主模型遇到 408/429/5xx 或超时会自动换备用模型，并在 cooldown 内优先使用备用模型，
 * 避免每次都先撞一遍主模型的限流。推理模型输出里的 &lt;think&gt; 段会被清掉。
 */
@Component
public class VisionLlmClient {

    private static final Logger log = LoggerFactory.getLogger(VisionLlmClient.class);
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final Pattern CODE_FENCE = Pattern.compile("^```[a-zA-Z]*\\s*|\\s*```$");

    private final AutoGameProperties.Vision config;
    private final RestClient restClient;
    private final AtomicReference<Instant> fallbackUntil = new AtomicReference<>(Instant.EPOCH);

    public VisionLlmClient(AutoGameProperties properties) {
        this.config = properties.vision();
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(config.timeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public Answer analyze(byte[] png, String prompt) {
        if (config.apiKey().isBlank()) {
            throw ToolException.badRequest("未配置视觉模型密钥：请 export ZHIPU_API_KEY=你的key 后重启服务"
                    + "（或改 application.yml 的 autogame.vision.api-key）");
        }
        String preferred = Instant.now().isBefore(fallbackUntil.get()) ? config.fallbackModel() : config.primaryModel();
        List<String> models = candidateModels(preferred);
        RuntimeException lastFailure = null;
        for (String model : models) {
            try {
                return call(model, png, prompt);
            } catch (RetryableFailure e) {
                lastFailure = e;
                fallbackUntil.set(Instant.now().plus(config.cooldown()));
                log.warn("模型 {} 暂不可用（{}），{}s 内优先使用备用模型", model, e.getMessage(),
                        config.cooldown().toSeconds());
            }
        }
        throw ToolException.internal("视觉模型调用失败：" + (lastFailure == null ? "无可用模型" : lastFailure.getMessage()),
                lastFailure);
    }

    private Answer call(String model, byte[] png, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("temperature", 0.1);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl(png))),
                        Map.of("type", "text", "text", prompt)))));

        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(config.baseUrl() + "/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String detail = "HTTP " + status + " " + abbreviate(e.getResponseBodyAsString());
            if (status == 408 || status == 429 || status >= 500) {
                throw new RetryableFailure(detail);
            }
            throw ToolException.badRequest("视觉模型拒绝了请求（" + model + "）：" + detail);
        } catch (ResourceAccessException e) {
            throw new RetryableFailure("网络异常或超时：" + e.getMessage());
        }
        return new Answer(extractContent(response), model);
    }

    private String extractContent(Map<?, ?> response) {
        Object choices = response == null ? null : response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw ToolException.internal("视觉模型返回结构异常：" + abbreviate(String.valueOf(response)));
        }
        Object message = list.getFirst() instanceof Map<?, ?> first ? first.get("message") : null;
        Object content = message instanceof Map<?, ?> map ? map.get("content") : null;
        String text = content instanceof List<?> parts ? joinParts(parts) : content == null ? "" : String.valueOf(content);
        String cleaned = clean(text);
        if (cleaned.isBlank()) {
            throw ToolException.internal("视觉模型返回了空内容");
        }
        return cleaned;
    }

    private String joinParts(List<?> parts) {
        StringBuilder builder = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map && map.get("text") != null) {
                builder.append(map.get("text")).append('\n');
            }
        }
        return builder.toString();
    }

    /** 去掉推理模型的 think 段和 markdown 代码围栏，方便直接当 JSON 解析。 */
    private String clean(String text) {
        String withoutThink = THINK_BLOCK.matcher(text).replaceAll("").strip();
        String withoutFence = CODE_FENCE.matcher(withoutThink).replaceAll("").strip();
        return withoutFence;
    }

    private List<String> candidateModels(String preferred) {
        List<String> models = new ArrayList<>(2);
        models.add(preferred);
        String other = preferred.equals(config.primaryModel()) ? config.fallbackModel() : config.primaryModel();
        if (!other.isBlank() && !models.contains(other)) {
            models.add(other);
        }
        return models;
    }

    private String dataUrl(byte[] png) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    /** 可重试的失败：限流、超时、上游 5xx。 */
    private static class RetryableFailure extends RuntimeException {
        RetryableFailure(String message) {
            super(message);
        }
    }

    /**
     * @param content 清洗后的模型输出
     * @param model   实际命中的模型名
     */
    public record Answer(String content, String model) {
    }
}
