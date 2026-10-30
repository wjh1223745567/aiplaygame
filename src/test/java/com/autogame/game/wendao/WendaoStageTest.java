package com.autogame.game.wendao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 阶段解析：模型回什么都得收敛到固定枚举，认不出来只能是 UNKNOWN（流程会只等不动）。
 */
class WendaoStageTest {

    @Test
    void 认识英文候选词() {
        assertEquals(WendaoStage.MAIN, WendaoStage.parse("MAIN"));
        assertEquals(WendaoStage.DIALOG, WendaoStage.parse("dialog"));
        assertEquals(WendaoStage.BATTLE, WendaoStage.parse("BATTLE"));
        assertEquals(WendaoStage.LOGIN, WendaoStage.parse("LOGIN"));
        assertEquals(WendaoStage.OFFLINE, WendaoStage.parse("OFFLINE"));
    }

    @Test
    void 认识中文与带标点的输出() {
        assertEquals(WendaoStage.MAIN, WendaoStage.parse("主界面。"));
        assertEquals(WendaoStage.DIALOG, WendaoStage.parse("这是弹窗，需要点确定"));
        assertEquals(WendaoStage.BATTLE, WendaoStage.parse("战斗中"));
        assertEquals(WendaoStage.LOGIN, WendaoStage.parse("登录界面（账号密码框）"));
        assertEquals(WendaoStage.OFFLINE, WendaoStage.parse("连接已断开"));
    }

    @Test
    void 回答里出现多个词时取最靠前的() {
        // 模型常把「先看到什么」写在前面，位置比词表顺序更可信
        assertEquals(WendaoStage.OFFLINE, WendaoStage.parse("OFFLINE 掉线了，不是主界面"));
        assertEquals(WendaoStage.DIALOG, WendaoStage.parse("DIALOG，点完之后是 MAIN"));
    }

    @Test
    void 空值与认不出来都是UNKNOWN() {
        assertEquals(WendaoStage.UNKNOWN, WendaoStage.parse(null));
        assertEquals(WendaoStage.UNKNOWN, WendaoStage.parse("  "));
        assertEquals(WendaoStage.UNKNOWN, WendaoStage.parse("一片森林"));
        assertEquals(WendaoStage.UNKNOWN, WendaoStage.parse("UNKNOWN"));
    }

    @Test
    void 在游戏里与需要登录的划分() {
        assertTrue(WendaoStage.MAIN.inGame());
        assertTrue(WendaoStage.DIALOG.inGame());
        assertTrue(WendaoStage.BATTLE.inGame());
        assertFalse(WendaoStage.LOGIN.inGame());
        // 在游戏里就不该再走登录，登录界面/掉线才走
        assertFalse(WendaoStage.MAIN.needsLogin());
        assertTrue(WendaoStage.LOGIN.needsLogin());
        assertTrue(WendaoStage.OFFLINE.needsLogin());
        assertFalse(WendaoStage.UNKNOWN.needsLogin());
    }
}
