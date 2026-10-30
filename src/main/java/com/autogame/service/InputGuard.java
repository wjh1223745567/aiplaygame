package com.autogame.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.CursorProbe;
import com.autogame.platform.DesktopRobot;
import com.autogame.platform.InputActivity;
import com.autogame.support.ToolException;

/**
 * 鼠标守卫：别人正在用输入设备、或光标被独占时不抢操作，直接回 423 让调用方暂停重试。
 *
 * <p>三级判据，从「确定被接管」到「可能在接管」：
 * <ol>
 *   <li>没有事件投递权限 → 合成鼠标事件会被系统丢弃，报错让用户去授权（500，重试无用）；</li>
 *   <li>光标挪不动 → 被全屏游戏/远控钳制，确定接管（423）；</li>
 *   <li>最近有真实输入 → 有人在操作，视为人工接管（423）；自己刚刚合成的动作不算；</li>
 *   <li>远控类进程在跑 → 只作为可疑信号，默认不拦（常驻驱动/服务会误报）。</li>
 * </ol>
 */
@Service
public class InputGuard {

    private static final Logger log = LoggerFactory.getLogger(InputGuard.class);

    /** 判断「最近的输入是不是自己造的」时允许的时间误差（系统时间戳精度有限）。 */
    private static final long OWN_INPUT_SLACK_MS = 60;

    private final InputActivity inputActivity;
    private final CursorProbe cursorProbe;
    private final DesktopRobot desktopRobot;

    public InputGuard(InputActivity inputActivity, CursorProbe cursorProbe, DesktopRobot desktopRobot) {
        this.inputActivity = inputActivity;
        this.cursorProbe = cursorProbe;
        this.desktopRobot = desktopRobot;
    }

    public void ensureNotCaptured(AutoGameProperties.Guard guard) {
        if (Boolean.TRUE.equals(guard.detectHijackers())) {
            List<String> hijackers = inputActivity.hijackers();
            if (!hijackers.isEmpty()) {
                throw ToolException.locked("鼠标可能已被远程控制接管：" + String.join("、", hijackers)
                        + "，已暂停动作，请断开远控后重试");
            }
        }
        long idle = inputActivity.idleMillis();
        if (idle >= 0 && idle < guard.idleThreshold().toMillis() && !isOwnInput(idle)) {
            throw ToolException.locked("检测到人工操作：" + idle + "ms 前刚有鼠标/键盘输入（阈值 "
                    + guard.idleThreshold().toMillis() + "ms），已暂停动作，请松开鼠标后重试");
        }
        if (Boolean.TRUE.equals(guard.cursorProbe())) {
            CursorProbe.Verdict verdict = cursorProbe.probe();
            if (verdict == CursorProbe.Verdict.NO_PERMISSION) {
                throw ToolException.internal("当前进程没有「辅助功能」权限，合成的鼠标事件会被系统丢弃："
                        + "请到 系统设置 → 隐私与安全性 → 辅助功能 勾选启动本服务的终端/IDE，然后重启服务");
            }
            if (verdict == CursorProbe.Verdict.LOCKED) {
                throw ToolException.locked("光标被其他程序锁定（全屏游戏或远程控制独占），已暂停动作");
            }
        }
        log.debug("输入未被接管，空闲 {}ms", idle);
    }

    /**
     * 最近的输入是不是本服务自己合成的。
     *
     * <p>系统只记录「最后一条输入事件」，探针挪光标、鼠标点击都会刷新它。两个时间是「多少毫秒前」，
     * 所以比大小就够：人只要在我们之后动过，最后一条事件就比我们自己那条更新，idle 会明显小于
     * synthetic，这时必须算人工接管；反过来 idle 更大，说明我们发出去的事件之后没人动过。
     * 不能只看两者差值的绝对大小，否则「人紧跟着我们的点击再动鼠标」会被当成自己人在动。
     */
    private boolean isOwnInput(long idle) {
        long synthetic = desktopRobot.sinceLastInputMillis();
        return synthetic >= 0 && idle + OWN_INPUT_SLACK_MS >= synthetic;
    }
}
