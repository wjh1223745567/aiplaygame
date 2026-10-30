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
public record AutoGameProperties(Vision vision, Capture capture, Mouse mouse, Guard guard) {

    public AutoGameProperties {
        vision = vision == null ? Vision.defaults() : vision;
        capture = capture == null ? Capture.defaults() : capture;
        mouse = mouse == null ? Mouse.defaults() : mouse;
        guard = guard == null ? Guard.defaults() : guard;
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

    /**
     * 动作前置保障：先确保应用已启动、已在顶层，再确认鼠标没被别人接管。
     *
     * @param windowTimeout   启动后等窗口出现的最长时长
     * @param idleThreshold   距上次真实输入小于该值就当作「人工正在操作」，暂停动作
     * @param detectHijackers 是否检查远程控制类进程；默认关，因为常驻驱动/服务会误报
     * @param cursorProbe     是否用「光标挪不挪得动」判断被独占；默认开
     */
    public record Guard(Duration windowTimeout, Duration idleThreshold, Boolean detectHijackers, Boolean cursorProbe) {

        private static final Duration DEFAULT_WINDOW_TIMEOUT = Duration.ofSeconds(8);
        private static final Duration DEFAULT_IDLE_THRESHOLD = Duration.ofMillis(800);
        private static final boolean DEFAULT_DETECT_HIJACKERS = false;
        private static final boolean DEFAULT_CURSOR_PROBE = true;

        public Guard {
            windowTimeout = windowTimeout == null ? DEFAULT_WINDOW_TIMEOUT : windowTimeout;
            idleThreshold = idleThreshold == null ? DEFAULT_IDLE_THRESHOLD : idleThreshold;
            detectHijackers = detectHijackers == null ? DEFAULT_DETECT_HIJACKERS : detectHijackers;
            cursorProbe = cursorProbe == null ? DEFAULT_CURSOR_PROBE : cursorProbe;
        }

        static Guard defaults() {
            return new Guard(DEFAULT_WINDOW_TIMEOUT, DEFAULT_IDLE_THRESHOLD, DEFAULT_DETECT_HIJACKERS,
                    DEFAULT_CURSOR_PROBE);
        }
    }
}
