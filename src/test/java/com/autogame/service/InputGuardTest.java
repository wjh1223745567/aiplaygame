package com.autogame.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.CursorProbe;
import com.autogame.platform.DesktopRobot;
import com.autogame.platform.InputActivity;
import com.autogame.support.ToolException;

class InputGuardTest {

    private static final Duration THRESHOLD = Duration.ofMillis(800);

    @Test
    void 空闲足够且光标能动时放行() {
        assertDoesNotThrow(() -> guard(5000, true).ensureNotCaptured(guardConfig(false, true)));
    }

    @Test
    void 刚有输入时暂停并提示人工操作() {
        ToolException error = assertThrows(ToolException.class,
                () -> guard(120, true).ensureNotCaptured(guardConfig(false, true)));
        assertEquals(423, error.status());
        assertTrue(error.getMessage().contains("人工操作"));
    }

    @Test
    void 光标挪不动时暂停() {
        ToolException error = assertThrows(ToolException.class,
                () -> guard(5000, false).ensureNotCaptured(guardConfig(false, true)));
        assertEquals(423, error.status());
        assertTrue(error.getMessage().contains("光标被其他程序锁定"));
    }

    @Test
    void 关掉光标探针后即使挪不动也不拦() {
        assertDoesNotThrow(() -> guard(5000, false).ensureNotCaptured(guardConfig(false, false)));
    }

    @Test
    void 开启远控检测时发现远控进程就暂停() {
        ToolException error = assertThrows(ToolException.class,
                () -> guard(5000, true, "TeamViewer").ensureNotCaptured(guardConfig(true, true)));
        assertEquals(423, error.status());
        assertTrue(error.getMessage().contains("远程控制"));
        assertTrue(error.getMessage().contains("TeamViewer"));
    }

    @Test
    void 默认不检查远控进程避免误报() {
        assertDoesNotThrow(() -> guard(5000, true, "TeamViewer").ensureNotCaptured(guardConfig(false, true)));
    }

    @Test
    void 判断不了空闲时间时不拦() {
        assertDoesNotThrow(() -> guard(-1, true).ensureNotCaptured(guardConfig(false, true)));
    }

    @Test
    void 自己刚合成的动作不算人工操作() {
        // 120ms 前的那次「输入」正是本服务自己挪光标造成的
        InputGuard guard = new InputGuard(new FakeActivity(120, new String[0]), () -> CursorProbe.Verdict.FREE,
                new FixedRobot(120));
        assertDoesNotThrow(() -> guard.ensureNotCaptured(guardConfig(false, true)));
    }

    @Test
    void 自己动过之后人又动了仍算人工操作() {
        // 系统记录的最近输入是 120ms 前，但本服务上次合成输入是 3000ms 前 → 那条是人的
        InputGuard guard = new InputGuard(new FakeActivity(120, new String[0]), () -> CursorProbe.Verdict.FREE,
                new FixedRobot(3000));
        ToolException error = assertThrows(ToolException.class, () -> guard.ensureNotCaptured(guardConfig(false, true)));
        assertEquals(423, error.status());
        assertTrue(error.getMessage().contains("人工操作"));
    }

    @Test
    void 没有辅助功能权限时报错让用户去授权() {
        InputGuard guard = new InputGuard(new FakeActivity(5000, new String[0]),
                () -> CursorProbe.Verdict.NO_PERMISSION, new DesktopRobot());
        ToolException error = assertThrows(ToolException.class, () -> guard.ensureNotCaptured(guardConfig(false, true)));
        assertEquals(500, error.status());
        assertTrue(error.getMessage().contains("辅助功能"));
    }

    private static AutoGameProperties.Guard guardConfig(boolean detectHijackers, boolean cursorProbe) {
        return new AutoGameProperties.Guard(Duration.ofSeconds(1), THRESHOLD, detectHijackers, cursorProbe);
    }

    private static InputGuard guard(long idleMillis, boolean cursorMovable, String... hijackers) {
        return new InputGuard(new FakeActivity(idleMillis, hijackers),
                () -> cursorMovable ? CursorProbe.Verdict.FREE : CursorProbe.Verdict.LOCKED, new DesktopRobot());
    }

    /** 伪造「本服务上次合成输入是什么时候」，用来验证归属判断。 */
    private static final class FixedRobot extends DesktopRobot {

        private final long since;

        private FixedRobot(long since) {
            this.since = since;
        }

        @Override
        public long sinceLastInputMillis() {
            return since;
        }
    }

    private static final class FakeActivity implements InputActivity {

        private final long idleMillis;
        private final List<String> hijackers;

        private FakeActivity(long idleMillis, String[] hijackers) {
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
