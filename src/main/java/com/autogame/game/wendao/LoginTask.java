package com.autogame.game.wendao;

/**
 * 执行一次「从登录界面进到游戏里」。
 *
 * <p>师门任务中途掉线时要重登，流程因此依赖这个动作而不是具体的登录实现。
 */
@FunctionalInterface
public interface LoginTask {

    WendaoLogin.Outcome login();
}
