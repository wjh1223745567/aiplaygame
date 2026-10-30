package com.autogame.game.wendao;

import org.springframework.stereotype.Component;

import com.autogame.aop.RequiresApp;
import com.autogame.service.function.AppFunction;

/**
 * 功能：自动跑师门任务。
 *
 * <pre>
 * POST /api/function/wendaoShimen   {"rounds":20}
 * </pre>
 *
 * <p>{@code rounds} 不传就用配置里的环数；超时/掉线重登上限同样在配置里。
 * 返回里带中止原因，脚本据此决定继续下一轮还是叫人。
 */
@Component
public class WendaoShimenFunction implements AppFunction<WendaoShimenFunction.Request, WendaoShimen.Result> {

    private final WendaoShimen shimen;

    public WendaoShimenFunction(WendaoShimen shimen) {
        this.shimen = shimen;
    }

    @Override
    public String name() {
        return "wendaoShimen";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    @RequiresApp(app = "${autogame.game.wendao.app}", title = "${autogame.game.wendao.title}",
            windowTimeout = WendaoProperties.LAUNCH_WINDOW_TIMEOUT_MS)
    public WendaoShimen.Result execute(Request request) {
        return shimen.run(request == null ? null : request.rounds());
    }

    /** @param rounds 要跑几环；为空或非正数时用配置值 */
    public record Request(Integer rounds) {
    }
}
