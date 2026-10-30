package com.autogame.game.wendao;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.support.ToolException;

/**
 * 师门任务循环：每轮看一眼画面，按阶段决定「点哪 / 等多久」，不做任何猜测。
 *
 * <ul>
 *   <li>主界面 → 点任务追踪栏里的师门任务，角色自动寻路；算完成一环；</li>
 *   <li>对话框 → 点「确定 / 接受 / 交任务」推进；</li>
 *   <li>战斗 → 只等，不点（回合操作交给游戏自己的挂机设置，避免误点技能）；</li>
 *   <li>登录界面 / 掉线 → 按上限自动重登；</li>
 *   <li>看不出来 → 只等，绝不乱点。</li>
 * </ul>
 *
 * <p>三种中止条件：跑满环数、整轮超时、掉线且重登次数用尽。中止原因会带回给调用方，
 * 便于脚本决定是继续下一轮还是叫人。
 */
@Service
public class WendaoShimen {

    private static final Logger log = LoggerFactory.getLogger(WendaoShimen.class);

    private final StageReader vision;
    private final SpotClicker window;
    private final LoginTask login;
    private final WendaoProperties config;

    public WendaoShimen(StageReader vision, SpotClicker window, LoginTask login, WendaoProperties config) {
        this.vision = vision;
        this.window = window;
        this.login = login;
        this.config = config;
    }

    /**
     * @param rounds 要跑几环；为空或非正数时用配置里的 {@code shimen.rounds}
     */
    public Result run(Integer rounds) {
        WendaoProperties.Shimen shimen = config.shimen();
        int target = rounds == null || rounds <= 0 ? shimen.rounds() : rounds;
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + shimen.totalTimeout().toMillis();

        int done = 0;
        int clicks = 0;
        int relogins = 0;
        WendaoStage stage = WendaoStage.UNKNOWN;
        String stopReason = "到 " + shimen.totalTimeout().toMillis() + "ms 仍没跑满 " + target + " 环";

        while (System.currentTimeMillis() < deadline) {
            if (done >= target) {
                stopReason = "已跑满 " + target + " 环";
                break;
            }
            stage = vision.detect("师门任务，第 " + (done + 1) + " 环");
            if (stage == WendaoStage.MAIN) {
                window.click(shimen.taskTracker());
                clicks++;
                done++;
                log.info("第 {}/{} 环：点任务栏寻路", done, target);
                sleep(shimen.roundInterval());
            } else if (stage == WendaoStage.DIALOG) {
                window.click(shimen.confirmButton());
                clicks++;
                sleep(shimen.roundInterval());
            } else if (stage == WendaoStage.BATTLE) {
                sleep(shimen.battleWait());
            } else if (stage.needsLogin()) {
                if (relogins >= shimen.reloginLimit()) {
                    stopReason = "停在 " + stage + "，已自动重登 " + relogins + " 次，交给人工";
                    break;
                }
                relogins++;
                log.warn("画面是 {}，第 {} 次自动登录", stage, relogins);
                login.login();
            } else {
                // 看不出来就只看不动，等下一次判定
                sleep(shimen.battleWait());
            }
        }

        long elapsed = System.currentTimeMillis() - startedAt;
        Result result = new Result(done >= target, done, target, clicks, relogins, stage, elapsed, stopReason);
        log.info("师门任务结束：{}", result);
        return result;
    }

    private static void sleep(Duration duration) {
        long millis = duration == null ? 0 : duration.toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ToolException.internal("师门任务被中断", e);
        }
    }

    /**
     * @param completed   是否跑满目标环数
     * @param roundsDone  实际完成环数
     * @param targetRounds 目标环数
     * @param clicks      总点击次数
     * @param relogins    自动重登次数
     * @param lastStage   最后一次看到的画面阶段
     * @param elapsedMs   总耗时
     * @param stopReason  中止原因（跑满 / 超时 / 掉线重登用尽）
     */
    public record Result(boolean completed, int roundsDone, int targetRounds, int clicks, int relogins,
            WendaoStage lastStage, long elapsedMs, String stopReason) {
    }
}
