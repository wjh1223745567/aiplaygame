package com.autogame.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * 本工具的外部配置，全部来自 application.yml 的 autogame 前缀，便于换模型/换机器只改配置。
 *
 * <p>注意：默认值用字面量常量而不是「静态 DEFAULTS 实例」，否则构造 DEFAULTS 时会读到尚未初始化的自身。
 */
@ConfigurationProperties(prefix = "autogame")
public record AutoGameProperties(Vision vision, Capture capture, Mouse mouse) {

    public AutoGameProperties {
        vision = vision == null ? Vision.defaults() : vision;
        capture = capture == null ? Capture.defaults() : capture;
        mouse = mouse == null ? Mouse.defaults() : mouse;
    }

    /**
     * 视觉大模型配置。base-url 指向任意 OpenAI 兼容端点，主模型失败时按 cooldown 切备用模型。
     */
    public record Vision(
            String baseUrl,
            String apiKey,
            String primaryModel,
            String fallbackModel,
            Duration timeout,
            Duration cooldown,
            DataSize maxImageBytes) {

        private static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
        private static final String DEFAULT_PRIMARY_MODEL = "glm-4v-flash";
        private static final String DEFAULT_FALLBACK_MODEL = "glm-4.1v-thinking-flash";
        private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
        private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(120);
        private static final DataSize DEFAULT_MAX_IMAGE = DataSize.ofMegabytes(8);

        public Vision {
            baseUrl = blankTo(baseUrl, DEFAULT_BASE_URL);
            apiKey = apiKey == null ? "" : apiKey.trim();
            primaryModel = blankTo(primaryModel, DEFAULT_PRIMARY_MODEL);
            fallbackModel = blankTo(fallbackModel, DEFAULT_FALLBACK_MODEL);
            timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
            cooldown = cooldown == null ? DEFAULT_COOLDOWN : cooldown;
            maxImageBytes = maxImageBytes == null ? DEFAULT_MAX_IMAGE : maxImageBytes;
        }

        static Vision defaults() {
            return new Vision(DEFAULT_BASE_URL, "", DEFAULT_PRIMARY_MODEL, DEFAULT_FALLBACK_MODEL,
                    DEFAULT_TIMEOUT, DEFAULT_COOLDOWN, DEFAULT_MAX_IMAGE);
        }
    }

    /**
     * 截图落盘配置：只有显式 save=true 时才写文件，避免把磁盘写满。
     */
    public record Capture(String debugDir, boolean saveByDefault) {

        private static final String DEFAULT_DEBUG_DIR = "debug";

        public Capture {
            debugDir = blankTo(debugDir, DEFAULT_DEBUG_DIR);
        }

        static Capture defaults() {
            return new Capture(DEFAULT_DEBUG_DIR, false);
        }
    }

    /**
     * 鼠标配置：移动默认带缓动，避免瞬移导致目标程序漏掉 hover 事件。
     */
    public record Mouse(Duration moveDuration) {

        private static final Duration DEFAULT_MOVE_DURATION = Duration.ofMillis(300);

        public Mouse {
            moveDuration = moveDuration == null ? DEFAULT_MOVE_DURATION : moveDuration;
        }

        static Mouse defaults() {
            return new Mouse(DEFAULT_MOVE_DURATION);
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
