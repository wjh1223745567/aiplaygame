package com.autogame.platform;

import java.awt.Dimension;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Toolkit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * 真实探针：挪几个像素再读回坐标，挪不动就认为光标被锁。
 *
 * <p>两个易错点都已处理：
 * <ul>
 *   <li>{@code Robot.mouseMove} 是异步的，立刻读 {@code MouseInfo} 拿到的还是旧坐标，
 *       所以要轮询几帧再判定，否则正常环境也会被误判成「被锁」；</li>
 *   <li>没有辅助功能权限时系统会静默丢弃合成事件，看起来和「被独占」一模一样，
 *       因此先用 {@code CGPreflightPostEventAccess} 把这种情况区分出来。</li>
 * </ul>
 * 探针挪完立刻还原，肉眼几乎看不出来；只有确认没人操作（空闲够久）时才会跑。
 */
@Service
public class DesktopCursorProbe implements CursorProbe {

    private static final Logger log = LoggerFactory.getLogger(DesktopCursorProbe.class);

    /** CoreGraphics 里只需要这一个函数：本进程能否向系统投递合成事件。 */
    interface CoreGraphics extends Library {

        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        boolean CGPreflightPostEventAccess();
    }

    /** 探针位移；太小会落在同一像素上，太大就会看得见。 */
    private static final int PROBE_OFFSET = 3;
    /** 允许误差：Retina 缩放与光标吸附可能带来 1~2px 偏差。 */
    private static final int TOLERANCE = 2;
    /** 轮询帧数与间隔：覆盖 AWT 事件的异步投递延迟（实测 1~2 帧即落点）。 */
    private static final int POLL_TIMES = 5;
    private static final long POLL_INTERVAL_MS = 20;

    private final DesktopRobot desktopRobot;

    public DesktopCursorProbe(DesktopRobot desktopRobot) {
        this.desktopRobot = desktopRobot;
    }

    @Override
    public Verdict probe() {
        if (!canPostEvents()) {
            return Verdict.NO_PERMISSION;
        }
        Point origin = MouseInfo.getPointerInfo().getLocation();
        // 光标贴着屏幕右/下边缘时往外挪会被钳住，换成往内挪
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int stepX = origin.x + PROBE_OFFSET + TOLERANCE < screen.width ? PROBE_OFFSET : -PROBE_OFFSET;
        int stepY = origin.y + PROBE_OFFSET + TOLERANCE < screen.height ? PROBE_OFFSET : -PROBE_OFFSET;
        int targetX = origin.x + stepX;
        int targetY = origin.y + stepY;
        try {
            desktopRobot.robot().mouseMove(targetX, targetY);
            Point last = origin;
            for (int i = 0; i < POLL_TIMES; i++) {
                sleep();
                last = MouseInfo.getPointerInfo().getLocation();
                if (near(last, targetX, targetY)) {
                    return Verdict.FREE;
                }
                if (!last.equals(origin)) {
                    // 动过但没落在目标上：多半是人同时在拖鼠标，交给空闲检测兜底
                    log.debug("光标已移动但未落点：目标 ({}, {}) 实际 ({}, {})", targetX, targetY, last.x, last.y);
                    return Verdict.FREE;
                }
            }
            log.warn("光标无法移动：目标 ({}, {}) 始终停在 ({}, {})，判定被独占", targetX, targetY, last.x, last.y);
            return Verdict.LOCKED;
        } finally {
            desktopRobot.robot().mouseMove(origin.x, origin.y);
        }
    }

    /** 判断不了（非 macOS 或系统调用失败）时按「有权限」处理，避免误拦。 */
    private static boolean canPostEvents() {
        try {
            return CoreGraphics.INSTANCE.CGPreflightPostEventAccess();
        } catch (RuntimeException | LinkageError e) {
            log.debug("无法查询事件投递权限：{}", e.toString());
            return true;
        }
    }

    private static boolean near(Point point, int x, int y) {
        return Math.abs(point.x - x) <= TOLERANCE && Math.abs(point.y - y) <= TOLERANCE;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
