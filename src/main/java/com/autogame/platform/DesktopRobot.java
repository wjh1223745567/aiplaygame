package com.autogame.platform;

import java.awt.AWTException;
import java.awt.Robot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.autogame.support.ToolException;

/**
 * 共享一个 AWT Robot 实例：鼠标控制与截图都走它，避免多处初始化。
 *
 * <p>懒加载：没有图形会话或权限未授予时，服务本身仍能启动，只在真正操作桌面时报错。
 */
@Component
public class DesktopRobot {

    private static final Logger log = LoggerFactory.getLogger(DesktopRobot.class);

    private volatile Robot robot;

    public Robot robot() {
        Robot local = robot;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (robot == null) {
                try {
                    Robot created = new Robot();
                    created.setAutoDelay(0);
                    created.setAutoWaitForIdle(false);
                    robot = created;
                    log.info("AWT Robot 初始化完成");
                } catch (AWTException e) {
                    throw ToolException.internal("初始化 AWT Robot 失败（无图形会话或缺少权限）：" + e.getMessage()
                            + "；请确认服务由桌面终端启动，且已授予「屏幕录制 / 辅助功能」权限", e);
                }
            }
            return robot;
        }
    }
}
