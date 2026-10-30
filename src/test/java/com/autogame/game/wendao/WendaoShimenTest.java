package com.autogame.game.wendao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 师门任务循环的分支：主界面寻路、对话框确认、战斗只等、看不清不动、掉线重登与三种中止。
 */
class WendaoShimenTest {

    private final List<WendaoProperties.Spot> clicks = new ArrayList<>();
    private final List<String> loginCalls = new ArrayList<>();

    @Test
    void 跑满环数就停() {
        WendaoShimen.Result result = run(stages(WendaoStage.MAIN, WendaoStage.MAIN, WendaoStage.MAIN), 2,
                Duration.ofSeconds(5), 1);

        assertTrue(result.completed());
        assertEquals(2, result.roundsDone());
        assertEquals(2, result.targetRounds());
        assertEquals(2, result.clicks());
        assertTrue(result.stopReason().contains("跑满"));
        // 主界面只点任务追踪栏，位置来自配置
        assertEquals(props(2, Duration.ofSeconds(5), 1).shimen().taskTracker(), clicks.get(0));
    }

    @Test
    void 对话框点确认并算作同一环() {
        WendaoShimen.Result result = run(stages(WendaoStage.DIALOG, WendaoStage.MAIN, WendaoStage.MAIN), 2,
                Duration.ofSeconds(5), 1);

        assertTrue(result.completed());
        assertEquals(3, result.clicks());
        assertEquals(2, result.roundsDone());
        assertEquals(props(2, Duration.ofSeconds(5), 1).shimen().confirmButton(), clicks.get(0));
    }

    @Test
    void 战斗只等不点() {
        WendaoShimen.Result result = run(context -> WendaoStage.BATTLE, 3, Duration.ofMillis(150), 1);

        assertFalse(result.completed());
        assertEquals(0, result.roundsDone());
        assertEquals(0, result.clicks());
        assertTrue(clicks.isEmpty());
        assertEquals(WendaoStage.BATTLE, result.lastStage());
    }

    @Test
    void 看不出来时也不点() {
        WendaoShimen.Result result = run(context -> WendaoStage.UNKNOWN, 3, Duration.ofMillis(150), 1);

        assertFalse(result.completed());
        assertEquals(0, result.clicks());
        assertEquals(WendaoStage.UNKNOWN, result.lastStage());
    }

    @Test
    void 掉线自动重登后继续跑() {
        WendaoShimen.Result result = run(stages(WendaoStage.LOGIN, WendaoStage.MAIN, WendaoStage.MAIN), 2,
                Duration.ofSeconds(5), 1);

        assertTrue(result.completed());
        assertEquals(1, result.relogins());
        assertEquals(1, loginCalls.size());
    }

    @Test
    void 重登次数用尽就交给人工() {
        WendaoShimen.Result result = run(context -> WendaoStage.OFFLINE, 3, Duration.ofSeconds(2), 1);

        assertFalse(result.completed());
        assertEquals(1, result.relogins());
        assertEquals(1, loginCalls.size());
        assertEquals(WendaoStage.OFFLINE, result.lastStage());
        assertTrue(result.stopReason().contains("人工"));
    }

    @Test
    void 不传环数就用配置值() {
        WendaoShimen.Result result = run(stages(WendaoStage.MAIN, WendaoStage.MAIN, WendaoStage.MAIN), null,
                Duration.ofSeconds(5), 1);

        assertTrue(result.completed());
        assertEquals(2, result.targetRounds());
    }

    @Test
    void 目标环数不合法时同样回落到配置() {
        WendaoShimen.Result result = run(stages(WendaoStage.MAIN, WendaoStage.MAIN), 0, Duration.ofSeconds(5), 1);

        assertEquals(2, result.targetRounds());
        assertTrue(result.completed());
    }

    private WendaoShimen.Result run(StageReader reader, Integer rounds, Duration totalTimeout, int reloginLimit) {
        WendaoProperties config = props(2, totalTimeout, reloginLimit);
        WendaoShimen shimen = new WendaoShimen(reader, spot -> {
            clicks.add(spot);
            return new Point(0, 0);
        }, () -> {
            loginCalls.add("login");
            return new WendaoLogin.Outcome(true, false, WendaoStage.LOGIN, WendaoStage.MAIN, 3, 1, "假登录");
        }, config);
        return shimen.run(rounds);
    }

    private static WendaoProperties props(int rounds, Duration totalTimeout, int reloginLimit) {
        WendaoProperties.Shimen shimen = new WendaoProperties.Shimen(null, null, rounds, Duration.ZERO,
                Duration.ofMillis(10), totalTimeout, reloginLimit);
        return new WendaoProperties("问道", "", null, null, shimen);
    }

    private static StageReader stages(WendaoStage... stages) {
        Deque<WendaoStage> queue = new ArrayDeque<>(List.of(stages));
        return context -> {
            WendaoStage next = queue.poll();
            if (next == null) {
                throw new IllegalStateException("阶段序列已经用完，流程多问了一次：" + context);
            }
            return next;
        };
    }
}
