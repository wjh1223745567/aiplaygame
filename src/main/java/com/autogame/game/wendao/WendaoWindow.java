package com.autogame.game.wendao;

import java.awt.Point;
import java.time.Duration;
import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;
import com.autogame.service.MouseService;
import com.autogame.support.ToolException;

/**
 * 游戏窗口的位置与点击：把配置里的比例坐标换算成屏幕绝对坐标，
 * 并在点击前确认窗口还顶在最前面（切出去以后按坐标点会点到别的程序上）。
 */
@Service
public class WendaoWindow implements SpotClicker {

    private static final Logger log = LoggerFactory.getLogger(WendaoWindow.class);
    private static final Duration HOVER = Duration.ofMillis(120);

    private final AppService appService;
    private final MouseService mouseService;
    private final WendaoProperties config;

    public WendaoWindow(AppService appService, MouseService mouseService, WendaoProperties config) {
        this.appService = appService;
        this.mouseService = mouseService;
        this.config = config;
    }

    /** 当前游戏窗口；找不到就报 404，提示先把客户端打开。 */
    public WindowInfo current() {
        return appService.windows().stream()
                .filter(window -> window.matches(config.app(), config.title()))
                .max(Comparator.comparingInt(WindowInfo::area))
                .orElseThrow(() -> ToolException.notFound("没找到「" + config.app() + "」的窗口"
                        + (config.title().isBlank() ? "" : "（标题含 " + config.title() + "）")
                        + "：请确认客户端已启动且没被最小化"));
    }

    /** 比例坐标 → 屏幕坐标。 */
    public Point absolute(WendaoProperties.Spot spot) {
        WindowInfo window = current();
        int x = window.x() + (int) Math.round(window.width() * spot.x());
        int y = window.y() + (int) Math.round(window.height() * spot.y());
        return new Point(x, y);
    }

    /** 点一下指定比例位置：先确保窗口在前，再缓动过去点。 */
    @Override
    public Point click(WendaoProperties.Spot spot) {
        ensureFront();
        Point point = absolute(spot);
        mouseService.moveTo(point.x, point.y, HOVER);
        mouseService.clickAt(point.x, point.y, "left", 1, null);
        log.debug("点击 {}({}) → 屏幕 ({},{})", config.app(), spot, point.x, point.y);
        return point;
    }

    /** 窗口不在最前就切回来；切不动就报错，绝不能盲点。 */
    public void ensureFront() {
        WindowInfo window = current();
        if (window.frontmost()) {
            return;
        }
        WindowInfo activated = appService.activate(config.app(), window.pid(), config.title());
        if (activated == null || !activated.frontmost()) {
            throw ToolException.internal("「" + config.app() + "」窗口切不到前台，可能有全屏程序挡着");
        }
        log.info("{} 不在前台，已切回", config.app());
    }
}
