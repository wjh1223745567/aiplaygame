package com.autogame.service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.WindowInfo;
import com.autogame.support.ToolException;

/**
 * 动作前置保障：按固定顺序把「能安全执行动作」的前提凑齐。
 *
 * <ol>
 *   <li>应用没启动 → 先启动并等窗口；</li>
 *   <li>窗口不在顶层 → 切到顶层；</li>
 *   <li>鼠标被人工操作或远程控制接管 → 回 423 暂停，不硬抢。</li>
 * </ol>
 */
@Service
public class AppPreflight {

    private static final Logger log = LoggerFactory.getLogger(AppPreflight.class);

    private final AppService appService;
    private final InputGuard inputGuard;
    private final AutoGameProperties.Guard guard;

    public AppPreflight(AppService appService, InputGuard inputGuard, AutoGameProperties properties) {
        this.appService = appService;
        this.inputGuard = inputGuard;
        this.guard = properties.guard();
    }

    /**
     * @param app   应用名（或 .app 路径）
     * @param title 窗口标题关键字，可空
     * @return 已在前台的目标窗口
     */
    public WindowInfo ensureReady(String app, String title) {
        return ensureReady(app, title, guard.windowTimeout());
    }

    /**
     * @param windowTimeout 启动后等窗口的最长时长；游戏类客户端启动慢，需要比全局配置更长；
     *                      传 null 表示用全局配置
     */
    public WindowInfo ensureReady(String app, String title, Duration windowTimeout) {
        Duration effective = windowTimeout == null ? guard.windowTimeout() : windowTimeout;
        WindowInfo window = ensureRunning(app, title, effective);
        WindowInfo foreground = ensureForeground(app, title, window);
        inputGuard.ensureNotCaptured(guard);
        log.debug("前置保障完成：{} 已在前台（{}）", app, foreground);
        return foreground;
    }

    /** 第一步：没启动就启动，启动后等窗口出现。 */
    private WindowInfo ensureRunning(String app, String title, Duration windowTimeout) {
        AppService.StatusResult status = appService.status(app, null, title);
        if (status.running()) {
            return pickFrontmost(status.windows());
        }
        log.info("{} 未运行，先启动", app);
        WindowInfo window = appService.launchAndWait(app, List.of(), Duration.ZERO, title, windowTimeout);
        if (window == null) {
            throw ToolException.internal(app + " 已发起启动，" + windowTimeout.toMillis() + "ms 内没等到窗口");
        }
        return window;
    }

    /** 第二步：不在顶层就切到顶层，并确认真的切上去了。 */
    private WindowInfo ensureForeground(String app, String title, WindowInfo window) {
        if (window != null && window.frontmost()) {
            return window;
        }
        log.info("{} 不在顶层，切到前台", app);
        WindowInfo activated = appService.activate(app, window == null ? null : window.pid(), title);
        if (activated == null) {
            throw ToolException.internal("无法把 " + app + " 切到顶层：没有可置顶的窗口");
        }
        if (!activated.frontmost()) {
            throw ToolException.internal(app + " 置顶后仍不在前台，可能有全屏/独占程序挡着");
        }
        return activated;
    }

    private static WindowInfo pickFrontmost(List<WindowInfo> windows) {
        return windows.stream()
                .max(Comparator.comparing(WindowInfo::frontmost).thenComparingInt(WindowInfo::area))
                .orElse(null);
    }
}
