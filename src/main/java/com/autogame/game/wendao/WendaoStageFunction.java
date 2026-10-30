package com.autogame.game.wendao;

import org.springframework.stereotype.Component;

import com.autogame.aop.RequiresApp;
import com.autogame.service.function.AppFunction;

/**
 * 功能：认一下问道客户端现在是什么页面，并给出要点一下的像素位置（只识别，不动鼠标）。
 *
 * <pre>
 * POST /api/function/wendaoStage   {}
 * </pre>
 *
 * <p>标了 {@code @RequiresApp}：客户端没启动时由切面自动「启动 → 等窗口 → 置顶」，
 * 调用方不用先手动开游戏。问道是慢启动的客户端，全局 8s 等不到窗口，
 * 单独给 60s。窗口应用在注解里用 {@code ${...}} 引用配置，实现类不注入配置。
 *
 * <p>返回里的 {@code spot} 是截图内的像素点，{@code screenPoint} 是同一个点的屏幕绝对坐标，
 * 可以直接喂 {@code POST /api/mouse/click}；本接口自己不会去点。
 * 提示词与模型原文都会写进日志，返回里也带 {@code answer}，方便调提示词。
 */
@Component
public class WendaoStageFunction implements AppFunction<WendaoStageFunction.Request, WendaoVision.Reading> {

    private final WendaoVision vision;

    public WendaoStageFunction(WendaoVision vision) {
        this.vision = vision;
    }

    @Override
    public String name() {
        return "wendaoStage";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    @RequiresApp(app = "${autogame.game.wendao.app}", title = "${autogame.game.wendao.title}",
            windowTimeout = 60_000)
    public WendaoVision.Reading execute(Request request) {
        return vision.detect();
    }

    /** 没有参数：识别哪个窗口由配置里的 {@code autogame.game.wendao.app/title} 决定。 */
    public record Request() {
    }
}
