package com.autogame.service;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.DesktopRobot;
import com.autogame.support.ToolException;

/**
 * 鼠标操作：带缓动的移动与点击。缓动是为了让目标程序收到完整的 hover/move 事件，
 * 瞬移容易被某些客户端忽略（点不中）。
 */
@Service
public class MouseService {

    private static final Logger log = LoggerFactory.getLogger(MouseService.class);
    private static final long STEP_MILLIS = 16;

    private final DesktopRobot desktopRobot;
    private final AutoGameProperties.Mouse config;

    public MouseService(DesktopRobot desktopRobot, AutoGameProperties properties) {
        this.desktopRobot = desktopRobot;
        this.config = properties.mouse();
    }

    /** duration 为空时用配置里的默认缓动时长。 */
    public Point moveTo(int x, int y, Duration duration) {
        Duration effective = duration == null ? config.moveDuration() : duration;
        Point from = currentPosition();
        long total = Math.max(0, effective.toMillis());
        if (from == null || total == 0) {
            desktopRobot.robot().mouseMove(x, y);
            return new Point(x, y);
        }
        int steps = (int) Math.max(1, Math.min(120, total / STEP_MILLIS));
        long perStep = total / steps;
        for (int i = 1; i <= steps; i++) {
            double ratio = (double) i / steps;
            int cx = (int) Math.round(from.x + (x - from.x) * ratio);
            int cy = (int) Math.round(from.y + (y - from.y) * ratio);
            desktopRobot.robot().mouseMove(cx, cy);
            sleep(perStep);
        }
        desktopRobot.robot().mouseMove(x, y);
        log.debug("鼠标移动到 ({},{})，用时 {}ms", x, y, total);
        return new Point(x, y);
    }

    /**
     * 在指定位置点击。
     *
     * @param button   left / right / middle
     * @param count    连击次数，1~5
     * @param interval 连击间隔，为空时用 120ms
     */
    public Point clickAt(int x, int y, String button, int count, Duration interval) {
        int mask = buttonMask(button);
        int clickCount = count <= 0 ? 1 : count;
        if (clickCount > 5) {
            throw ToolException.badRequest("count 最多 5 次，收到：" + count);
        }
        long gap = interval == null ? 120 : Math.max(0, interval.toMillis());
        desktopRobot.robot().mouseMove(x, y);
        sleep(60);
        for (int i = 0; i < clickCount; i++) {
            desktopRobot.robot().mousePress(mask);
            desktopRobot.robot().mouseRelease(mask);
            if (i < clickCount - 1) {
                sleep(gap);
            }
        }
        log.info("点击 ({},{}) button={} count={}", x, y, button == null ? "left" : button, clickCount);
        return new Point(x, y);
    }

    public Point currentPosition() {
        try {
            return MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int buttonMask(String button) {
        String normalized = button == null || button.isBlank() ? "left" : button.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "left" -> InputEvent.BUTTON1_DOWN_MASK;
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            default -> throw ToolException.badRequest("button 只支持 left/right/middle，收到：" + button);
        };
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ToolException.internal("点击被中断", e);
        }
    }
}
