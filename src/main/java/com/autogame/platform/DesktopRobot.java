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
 *
 * <p>每次合成输入都会记一笔时间（{@link #sinceLastInputMillis()}），因为系统把「合成的输入」
 * 和「人的输入」记在同一份时间戳里，守卫要靠它区分「刚才是自己在动」和「有人在操作」。
 */
@Component
public class DesktopRobot {

    private static final Logger log = LoggerFactory.getLogger(DesktopRobot.class);

    private volatile TrackingRobot robot;

    public Robot robot() {
        TrackingRobot local = robot;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (robot == null) {
                try {
                    TrackingRobot created = new TrackingRobot();
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

    /** @return 距上一次本服务合成输入事件的毫秒数；从未发过返回 -1 */
    public long sinceLastInputMillis() {
        TrackingRobot local = robot;
        long at = local == null ? 0L : local.lastInputAt;
        return at == 0L ? -1 : System.currentTimeMillis() - at;
    }

    /** 在共享实例上记录每次合成输入的时间，探针/鼠标服务都走这里，一处不漏。 */
    private static final class TrackingRobot extends Robot {

        private volatile long lastInputAt;

        private TrackingRobot() throws AWTException {
            super();
        }

        @Override
        public synchronized void mouseMove(int x, int y) {
            super.mouseMove(x, y);
            touch();
        }

        @Override
        public synchronized void mousePress(int buttons) {
            super.mousePress(buttons);
            touch();
        }

        @Override
        public synchronized void mouseRelease(int buttons) {
            super.mouseRelease(buttons);
            touch();
        }

        @Override
        public synchronized void mouseWheel(int wheelAmt) {
            super.mouseWheel(wheelAmt);
            touch();
        }

        @Override
        public synchronized void keyPress(int keycode) {
            super.keyPress(keycode);
            touch();
        }

        @Override
        public synchronized void keyRelease(int keycode) {
            super.keyRelease(keycode);
            touch();
        }

        private void touch() {
            lastInputAt = System.currentTimeMillis();
        }
    }
}
