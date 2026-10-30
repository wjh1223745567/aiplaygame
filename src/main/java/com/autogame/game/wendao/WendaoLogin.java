package com.autogame.game.wendao;

import java.awt.event.KeyEvent;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.service.KeyboardService;
import com.autogame.support.ToolException;

/**
 * 登录流程：填账号密码 → 点登录 → 等进到游戏主界面。
 *
 * <p>顺序上先看画面再动手：已经在游戏里就直接跳过，避免把正在挂机的角色点乱。
 * 登录按钮点完之后只「等 + 点弹窗」，靠视觉判定阶段，不靠固定等待，
 * 所以客户端更新慢一点也不会误判成失败。
 *
 * <p>坐标全是 {@link WendaoProperties.Login} 里的窗口比例，换机器只要改配置。
 */
@Service
public class WendaoLogin implements LoginTask {

    private static final Logger log = LoggerFactory.getLogger(WendaoLogin.class);

    /** 登录后允许连点「进服 / 公告确定」的次数上限，防止停在登录界面无限点。 */
    private static final int MAX_PROMPTS = 3;

    private final WendaoWindow window;
    private final WendaoVision vision;
    private final KeyboardService keyboard;
    private final WendaoProperties config;

    public WendaoLogin(WendaoWindow window, WendaoVision vision, KeyboardService keyboard, WendaoProperties config) {
        this.window = window;
        this.vision = vision;
        this.keyboard = keyboard;
        this.config = config;
    }

    @Override
    public Outcome login() {
        long startedAt = System.currentTimeMillis();
        WendaoStage before = vision.detect("登录流程");
        if (before.inGame()) {
            log.info("画面已是 {}，跳过登录", before);
            return new Outcome(true, true, before, before, 0, 0, "已经在游戏里，跳过登录");
        }
        if (before == WendaoStage.DIALOG) {
            // 登录前就弹对话框，多是「发现新版本 取消/确认」：点哪边都不可逆（开始下载 / 直接退出），
            // 所以不替用户做选择，直接暂停等人处理
            throw ToolException.locked("「" + config.app() + "」停在更新/公告弹窗，自动流程不会替你点："
                    + "点「确认」会开始下载、点「取消」会退出游戏。手动处理一次再调用即可");
        }
        WendaoProperties.Account account = config.account();
        if (!account.configured()) {
            throw ToolException.badRequest("没配置账号密码：填 autogame.game.wendao.account.user/password"
                    + "（或用环境变量 WENDAO_USER / WENDAO_PASSWORD），否则只能手工登录");
        }

        WendaoProperties.Login login = config.login();
        window.ensureFront();
        int clicks = 0;
        clicks += fill(login.accountBox(), account.user());
        clicks += fill(login.passwordBox(), account.password());
        window.click(login.loginButton());
        clicks++;
        log.info("账号密码已填、登录已点，最多等 {} 进游戏", login.readyTimeout());

        int prompts = 0;
        WendaoStage stage = before;
        long deadline = System.currentTimeMillis() + login.readyTimeout().toMillis();
        while (System.currentTimeMillis() < deadline) {
            sleep(login.pollInterval());
            stage = vision.detect("登录流程");
            if (stage.inGame()) {
                long elapsed = System.currentTimeMillis() - startedAt;
                log.info("登录成功：{}ms 后进入 {}（共点 {} 下）", elapsed, stage, clicks);
                return new Outcome(true, false, before, stage, clicks, elapsed, "登录成功");
            }
            if (prompts < MAX_PROMPTS && stage == WendaoStage.DIALOG) {
                window.click(login.noticeButton());
                clicks++;
                prompts++;
            } else if (prompts < MAX_PROMPTS && stage == WendaoStage.LOGIN) {
                // 登录按钮点完还停在登录/选服界面：点一下「进入游戏」，点够次数就只等不动
                window.click(login.enterButton());
                clicks++;
                prompts++;
            }
        }
        throw ToolException.internal("点完登录 " + login.readyTimeout().toMillis() + "ms 还没进到主界面，"
                + "当前画面是 " + stage + "；若卡在验证码/异地登录提示，请人工处理一次，"
                + "或校正 autogame.game.wendao.login 下的坐标");
    }

    /** 点中输入框 → 全选清空 → 敲入文本；返回点击数。 */
    private int fill(WendaoProperties.Spot box, String text) {
        window.click(box);
        clear();
        keyboard.type(text);
        return 1;
    }

    /**
     * 清空输入框：客户端不会自己清掉上次的输入，直接敲会拼到旧值后面。
     * 先用 ⌘A 全选再退格；个别客户端不认 ⌘A，这种情况下把框先空着跑一次即可。
     */
    private void clear() {
        keyboard.chord(KeyEvent.VK_META, KeyEvent.VK_A);
        keyboard.tap(KeyEvent.VK_BACK_SPACE);
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
            throw ToolException.internal("等待登录界面时被中断", e);
        }
    }

    /**
     * @param loggedIn  最终是否在游戏里（含「本来就在」）
     * @param skipped   是否因为已经在游戏里而跳过
     * @param before    开始时的画面阶段
     * @param after     结束时的画面阶段
     * @param clicks    本次点击次数
     * @param elapsedMs 耗时
     * @param note      一句话说明，便于调用方/日志展示
     */
    public record Outcome(boolean loggedIn, boolean skipped, WendaoStage before, WendaoStage after, int clicks,
            long elapsedMs, String note) {
    }
}
