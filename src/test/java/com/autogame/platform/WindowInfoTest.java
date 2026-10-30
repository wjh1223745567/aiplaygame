package com.autogame.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowInfoTest {

    private final WindowInfo game = new WindowInfo("问道", "问道 - 主界面", 4321, 100, 80, 1280, 800, true, 42);

    @Test
    void 空条件匹配任意窗口() {
        assertTrue(game.matches(null, null));
        assertTrue(game.matches("", "  "));
    }

    @Test
    void 进程名与标题都做包含匹配且忽略大小写() {
        assertTrue(game.matches("问", null));
        assertTrue(game.matches(null, "主界面"));
        assertTrue(game.matches("问道", "主界面"));
        assertFalse(game.matches("梦幻", null));
        assertFalse(game.matches(null, "背包"));
    }

    @Test
    void 面积用于在多个候选中挑最大窗口() {
        assertEquals(1280 * 800, game.area());
    }
}
