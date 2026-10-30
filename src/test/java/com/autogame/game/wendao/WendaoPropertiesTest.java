package com.autogame.game.wendao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 配置默认值与兜底：yml 里漏写或写错时，流程要能跑起来而不是抛空指针。
 */
class WendaoPropertiesTest {

    @Test
    void 全空时给出可用的默认值() {
        WendaoProperties config = new WendaoProperties(null, null, null, null, null);

        assertEquals("问道", config.app());
        assertEquals("", config.title());
        assertFalse(config.account().configured());
        assertNotNull(config.login().accountBox());
        assertNotNull(config.login().noticeButton());
        assertEquals(3, config.login().readyTimeout().toMinutes());
        assertEquals(20, config.shimen().rounds());
        assertEquals(1, config.shimen().reloginLimit());
        assertEquals(Duration.ofMinutes(45), config.shimen().totalTimeout());
    }

    @Test
    void 启动等窗口的时间比桌面应用宽裕得多() {
        assertTrue(WendaoProperties.LAUNCH_WINDOW_TIMEOUT_MS >= 60_000);
    }

    @Test
    void 环数写了0或负数就回落到默认() {
        WendaoProperties config = withShimen(0, Duration.ofMinutes(1), 1);
        assertEquals(20, config.shimen().rounds());

        WendaoProperties negative = withShimen(-3, Duration.ofMinutes(1), -1);
        assertEquals(20, negative.shimen().rounds());
        assertEquals(1, negative.shimen().reloginLimit());
    }

    @Test
    void 环数可以显式指定() {
        assertEquals(8, withShimen(8, Duration.ofMinutes(1), 2).shimen().rounds());
    }

    @Test
    void 账号密码都填了才算配置好() {
        assertTrue(new WendaoProperties.Account(" 13800000000 ", "pwd").configured());
        assertEquals("13800000000", new WendaoProperties.Account(" 13800000000 ", "pwd").user());
        assertFalse(new WendaoProperties.Account("13800000000", "").configured());
        assertFalse(new WendaoProperties.Account("", "pwd").configured());
    }

    @Test
    void 坐标必须落在窗口内() {
        assertThrows(IllegalArgumentException.class, () -> new WendaoProperties.Spot(1.2, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new WendaoProperties.Spot(0.5, -0.1));
        assertEquals(0.42, new WendaoProperties.Spot(0.42, 0.66).x());
    }

    private static WendaoProperties withShimen(Integer rounds, Duration totalTimeout, int reloginLimit) {
        WendaoProperties.Shimen shimen = new WendaoProperties.Shimen(null, null, rounds, Duration.ZERO,
                Duration.ZERO, totalTimeout, reloginLimit);
        return new WendaoProperties("问道", "", null, null, shimen);
    }
}
