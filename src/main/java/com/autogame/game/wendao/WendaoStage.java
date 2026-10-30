package com.autogame.game.wendao;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 截图里「现在处于哪个阶段」的判断结果，是流程分支的唯一依据。
 *
 * <p>阶段由视觉模型给出，但取值收敛到这里，模型回中文/英文、带标点都能认；
 * 认不出来就是 {@link #UNKNOWN}——这时流程只等待、绝不乱点，避免误操作。
 */
public enum WendaoStage {

    /** 游戏主界面：没战斗、没对话框，可以点任务栏去寻路。 */
    MAIN,

    /** 有对话框/弹窗需要点一下（接任务、交任务、确认提示）。 */
    DIALOG,

    /** 战斗中或挂机回合：只等待，不做任何输入。 */
    BATTLE,

    /** 停在登录界面：账号密码框、进入游戏按钮。 */
    LOGIN,

    /** 掉线、连接断开、错误提示。 */
    OFFLINE,

    /** 看不出来。 */
    UNKNOWN;

    /** 候选词表；按出现位置取最靠前的那个，兼容模型输出「MAIN。游戏主界面...」这类原文。 */
    private static final Map<String, WendaoStage> TOKENS = tokens();

    /** 已经在游戏世界里（主界面、对话、战斗都算），不需要再登录。 */
    public boolean inGame() {
        return this == MAIN || this == DIALOG || this == BATTLE;
    }

    /** 需要重新登录：停在登录界面或已掉线。 */
    public boolean needsLogin() {
        return this == LOGIN || this == OFFLINE;
    }

    /** 从模型输出里解析阶段；解析不出来返回 {@link #UNKNOWN}。 */
    public static WendaoStage parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return UNKNOWN;
        }
        String upper = answer.toUpperCase(Locale.ROOT);
        WendaoStage best = UNKNOWN;
        int bestIndex = Integer.MAX_VALUE;
        for (Map.Entry<String, WendaoStage> entry : TOKENS.entrySet()) {
            int index = upper.indexOf(entry.getKey());
            if (index >= 0 && index < bestIndex) {
                bestIndex = index;
                best = entry.getValue();
            }
        }
        return best;
    }

    private static Map<String, WendaoStage> tokens() {
        Map<String, WendaoStage> map = new LinkedHashMap<>();
        map.put("MAIN", MAIN);
        map.put("BATTLE", BATTLE);
        map.put("DIALOG", DIALOG);
        map.put("LOGIN", LOGIN);
        map.put("OFFLINE", OFFLINE);
        map.put("UNKNOWN", UNKNOWN);
        map.put("主界面", MAIN);
        map.put("战斗", BATTLE);
        map.put("对话框", DIALOG);
        map.put("弹窗", DIALOG);
        map.put("登录", LOGIN);
        map.put("掉线", OFFLINE);
        map.put("断开", OFFLINE);
        return Map.copyOf(map);
    }
}
