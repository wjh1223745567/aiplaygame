package com.autogame.game.wendao;

/**
 * 读当前画面属于哪个阶段。
 *
 * <p>抽成接口是为了让流程逻辑（登录等待、师门循环）可以脱离视觉模型单测：
 * 测试里给一串固定阶段，就能验证分支与计数，不用真调大模型。
 */
@FunctionalInterface
public interface StageReader {

    /**
     * @param context 当前正在做什么，会拼进提示词帮助模型聚焦
     */
    WendaoStage detect(String context);
}
