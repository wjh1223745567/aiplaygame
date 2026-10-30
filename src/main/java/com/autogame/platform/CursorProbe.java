package com.autogame.platform;

/**
 * 光标锁检测：判断程序还能不能把光标挪到指定位置。
 *
 * <p>被全屏游戏或远程控制独占时，合成移动会被系统丢掉，光标留在原地，据此认定鼠标被接管。
 */
@FunctionalInterface
public interface CursorProbe {

    /** 探针结论：能动、被钳制、还是本程序根本没有投递合成事件的权限。 */
    enum Verdict {

        /** 光标能动，可以操作。 */
        FREE,

        /** 光标被别的程序钳制（全屏游戏、远程控制），暂停动作等它释放。 */
        LOCKED,

        /** 本程序没有「辅助功能 / 事件投递」权限，合成事件会被系统静默丢弃，重试无用。 */
        NO_PERMISSION
    }

    Verdict probe();
}
