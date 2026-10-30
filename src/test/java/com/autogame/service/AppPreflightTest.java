package com.autogame.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.CursorProbe;
import com.autogame.platform.DesktopPlatform;
import com.autogame.platform.DesktopRobot;
import com.autogame.platform.InputActivity;
import com.autogame.platform.WindowInfo;
import com.autogame.support.ToolException;

/**
 * 验证前置保障的三步顺序：启动 → 置顶 → 接管检测。
 */
class AppPreflightTest {

    private static final Duration IDLE_THRESHOLD = Duration.ofMillis(800);

    @Test
    void 未启动时先启动再放行() {
        FakePlatform platform = new FakePlatform();
        WindowInfo window = preflight(platform, new FakeActivity(5000)).ensureReady("Calculator", null);

        assertNotNull(window);
        assertTrue(window.frontmost());
        assertEquals(1, platform.launches);
        assertEquals(0, platform.activations);
    }

    @Test
    void 已运行但不在顶层时切到前台() {
        FakePlatform platform = new FakePlatform();
        platform.windows.add(window("Calculator", 4321, false));

        WindowInfo window = preflight(platform, new FakeActivity(5000)).ensureReady("Calculator", null);

        assertEquals(1, platform.activations);
        assertEquals(0, platform.launches);
        assertTrue(window.frontmost());
    }

    @Test
    void 人工刚操作过则在启动置顶之后暂停() {
        FakePlatform platform = new FakePlatform();
        platform.windows.add(window("Calculator", 4321, false));

        ToolException error = assertThrows(ToolException.class,
                () -> preflight(platform, new FakeActivity(120)).ensureReady("Calculator", null));

        assertEquals(423, error.status());
        // 顺序：先置顶，再判接管
        assertEquals(1, platform.activations);
        assertTrue(platform.windows.stream().allMatch(WindowInfo::frontmost));
    }

    @Test
    void 远程控制接管时暂停() {
        FakePlatform platform = new FakePlatform();
        platform.windows.add(window("Calculator", 4321, true));

        AutoGameProperties.Guard guard = new AutoGameProperties.Guard(Duration.ofSeconds(1), IDLE_THRESHOLD, true, true);
        AppPreflight preflight = new AppPreflight(new AppService(platform),
                new InputGuard(new FakeActivity(5000, "AnyDesk"), () -> CursorProbe.Verdict.FREE, new DesktopRobot()),
                properties(guard));
        ToolException error = assertThrows(ToolException.class, () -> preflight.ensureReady("Calculator", null));

        assertEquals(423, error.status());
        assertTrue(error.getMessage().contains("AnyDesk"));
    }

    @Test
    void 光标被锁时暂停() {
        FakePlatform platform = new FakePlatform();
        platform.windows.add(window("Calculator", 4321, true));

        ToolException error = assertThrows(ToolException.class,
                () -> preflight(platform, new FakeActivity(5000), () -> CursorProbe.Verdict.LOCKED)
                        .ensureReady("Calculator", null));

        assertEquals(423, error.status());
        assertTrue(error.getMessage().contains("光标被其他程序锁定"));
    }

    @Test
    void 置顶失败时报错而不是继续执行() {
        FakePlatform platform = new FakePlatform();
        platform.windows.add(window("Calculator", 4321, false));
        platform.activateReturnsNull = true;

        ToolException error = assertThrows(ToolException.class,
                () -> preflight(platform, new FakeActivity(5000)).ensureReady("Calculator", null));

        assertEquals(500, error.status());
        assertTrue(error.getMessage().contains("无法把 Calculator 切到顶层"));
    }

    @Test
    void 启动后等不到窗口时报错() {
        FakePlatform platform = new FakePlatform();
        platform.launchCreatesWindow = false;

        ToolException error = assertThrows(ToolException.class,
                () -> preflight(platform, new FakeActivity(5000)).ensureReady("Calculator", null));

        assertEquals(500, error.status());
        assertTrue(error.getMessage().contains("没等到窗口"));
    }

    private static AppPreflight preflight(DesktopPlatform platform, InputActivity activity) {
        return preflight(platform, activity, () -> CursorProbe.Verdict.FREE);
    }

    private static AppPreflight preflight(DesktopPlatform platform, InputActivity activity, CursorProbe cursorProbe) {
        AutoGameProperties.Guard guard = new AutoGameProperties.Guard(Duration.ofSeconds(1), IDLE_THRESHOLD, true, true);
        return new AppPreflight(new AppService(platform),
                new InputGuard(activity, cursorProbe, new DesktopRobot()), properties(guard));
    }

    private static AutoGameProperties properties(AutoGameProperties.Guard guard) {
        return new AutoGameProperties(null, null, null, guard);
    }

    private static WindowInfo window(String app, long pid, boolean frontmost) {
        return new WindowInfo(app, "", pid, 0, 0, 100, 100, frontmost, pid);
    }

    /** 可控的桌面平台替身：只模拟窗口存在性、启动与置顶。 */
    private static final class FakePlatform implements DesktopPlatform {

        private final List<WindowInfo> windows = new ArrayList<>();
        private int launches;
        private int activations;
        private boolean launchCreatesWindow = true;
        private boolean activateReturnsNull;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public void launch(String app, List<String> args, Duration wait) {
            launches++;
            if (launchCreatesWindow) {
                windows.clear();
                windows.add(window(app, 9001, true));
            }
        }

        @Override
        public List<WindowInfo> windows() {
            return List.copyOf(windows);
        }

        @Override
        public WindowInfo activate(String app, Long pid, String title) {
            activations++;
            if (activateReturnsNull) {
                return null;
            }
            WindowInfo current = windows.getFirst();
            WindowInfo refreshed = window(current.app(), current.pid(), true);
            windows.set(0, refreshed);
            return refreshed;
        }

        @Override
        public List<Long> findPids(String app) {
            return windows.stream().map(WindowInfo::pid).distinct().toList();
        }

        @Override
        public boolean close(long pid, Duration grace) {
            return windows.removeIf(window -> window.pid() == pid);
        }
    }

    private static final class FakeActivity implements InputActivity {

        private final long idleMillis;
        private final List<String> hijackers;

        private FakeActivity(long idleMillis, String... hijackers) {
            this.idleMillis = idleMillis;
            this.hijackers = List.of(hijackers);
        }

        @Override
        public long idleMillis() {
            return idleMillis;
        }

        @Override
        public List<String> hijackers() {
            return hijackers;
        }
    }
}
