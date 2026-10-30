package com.autogame.game.wendao;

import java.awt.Point;

/**
 * 点一下游戏窗口里的某个相对位置。
 *
 * <p>与 {@link StageReader} 同样的目的：流程只依赖「能点」这件事，
 * 真实实现搬鼠标，测试实现只记账。
 */
@FunctionalInterface
public interface SpotClicker {

    /**
     * @return 实际点击的屏幕坐标
     */
    Point click(WendaoProperties.Spot spot);
}
