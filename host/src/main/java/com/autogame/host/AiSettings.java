package com.autogame.host;

import com.autogame.client.McpClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 服务配置：读 <code>host/config.json</code> 的 llm 段（与 Python 侧共用同一份）。
 *
 * <p>环境变量 {@code LLM_API_KEY} 优先级高于配置文件，方便临时换 key。
 * 配置缺失或非法时回落到代码默认值，不阻断服务启动。
 */
@Component
public class AiSettings {

    private static final Logger log = LoggerFactory.getLogger(AiSettings.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4";
    private static final String DEFAULT_MODEL = "glm-4v-flash";

    private final Path projectRoot;
    private final String apiBase;
    private final String apiKey;
    private final String model;
    private final double timeoutSeconds;
    private final double temperature;
    private final int maxImageSide;
    private final int maxRounds;
    private final boolean visionByDefault;

    public AiSettings() {
        this.projectRoot = McpClientFactory.resolveProjectRoot();
        JsonNode llm = JSON.createObjectNode();
        Path config = projectRoot.resolve("host").resolve("config.json");
        if (!Files.exists(config)) {
            // 兼容搬移前的旧布局：配置曾放在项目根
            Path legacy = projectRoot.resolve("config.json");
            if (Files.exists(legacy)) {
                config = legacy;
            }
        }
        if (Files.exists(config)) {
            try {
                llm = JSON.readTree(config.toFile()).path("llm");
                log.info("AI 配置: {}", config);
            } catch (Exception e) {
                log.warn("解析 {} 失败，AI 服务使用默认配置: {}", config, e.toString());
            }
        }
        this.apiBase = stripTrailingSlash(text(llm, "api_base", DEFAULT_BASE));
        this.model = text(llm, "model", DEFAULT_MODEL);
        String fromFile = text(llm, "api_key", "");
        String fromEnv = System.getenv("LLM_API_KEY");
        this.apiKey = fromEnv != null && !fromEnv.isBlank() ? fromEnv.trim() : fromFile;
        this.timeoutSeconds = number(llm, "timeout", 30.0);
        this.temperature = number(llm, "temperature", 0.0);
        this.maxImageSide = (int) number(llm, "max_image_side", 1024);
        this.maxRounds = (int) number(llm, "chat_max_rounds", 6);
        this.visionByDefault = flag(llm, "chat_vision", true);
        if (!ready()) {
            log.warn("未配置 llm.api_key（host/config.json 或 LLM_API_KEY），对话模式将不可用");
        }
    }

    /** 密钥是否可用。 */
    public boolean ready() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public String apiBase() {
        return apiBase;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public double timeoutSeconds() {
        return timeoutSeconds;
    }

    public double temperature() {
        return temperature;
    }

    public int maxImageSide() {
        return maxImageSide;
    }

    /** 单轮对话最多执行几次工具。 */
    public int maxRounds() {
        return maxRounds;
    }

    /** 默认是否把当前游戏截图一起发给模型。 */
    public boolean visionByDefault() {
        return visionByDefault;
    }

    // ---------- 取值辅助 ----------

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.path(field);
        String s = v.isMissingNode() || v.isNull() ? "" : v.asText("").trim();
        return s.isEmpty() ? fallback : s;
    }

    private static double number(JsonNode node, String field, double fallback) {
        JsonNode v = node.path(field);
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            try {
                return Double.parseDouble(v.asText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean flag(JsonNode node, String field, boolean fallback) {
        JsonNode v = node.path(field);
        return v.isBoolean() ? v.asBoolean() : fallback;
    }

    private static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
