package com.autogame.game.wendao;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 「问道」专属配置：只有识别哪个应用的窗口。
 *
 * <p>这里刻意什么都没有：本项目对问道只做页面检测，不动鼠标，
 * 所以既没有坐标也没有流程参数（环数、重试间隔之类）。
 * 以后要加自动化动作，再按需补时间段配置，别提前占位。
 */
@ConfigurationProperties(prefix = "autogame.game.wendao")
public record WendaoProperties(String app, String title) {

    public WendaoProperties {
        app = blankTo(app, "问道");
        title = title == null ? "" : title.trim();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
