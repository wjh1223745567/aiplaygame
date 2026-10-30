package com.autogame.service.function;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.autogame.aop.RequiresApp;
import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;
import com.autogame.support.ToolException;

/**
 * 示例功能：确认目标应用已在前台，并返回它的窗口坐标。
 *
 * <p>与 {@link CalculatorFunction} 不同，这个方法标了 {@link RequiresApp}，
 * 切面会先跑一遍「启动 → 置顶 → 鼠标接管检测」；检测到人工操作时直接回 423，方法体不会执行。
 *
 * <pre>
 * POST /api/function/activeWindow  {"app":"Calculator"}
 * </pre>
 */
@Component
public class ActiveWindowFunction implements AppFunction<ActiveWindowFunction.Request, ActiveWindowFunction.Response> {

    private final AppService appService;

    public ActiveWindowFunction(AppService appService) {
        this.appService = appService;
    }

    @Override
    public String name() {
        return "activeWindow";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    @RequiresApp(app = "#request?.app()", title = "#request?.title()")
    public Response execute(Request request) {
        if (request == null || request.app() == null || request.app().isBlank()) {
            throw ToolException.badRequest("app 不能为空：先告诉我目标应用，我才能确认它在前台");
        }
        long startedAt = System.currentTimeMillis();
        AppService.StatusResult status = appService.status(request.app(), null, request.title());
        WindowInfo window = status.windows().stream()
                .max(Comparator.comparing(WindowInfo::frontmost).thenComparingInt(WindowInfo::area))
                .orElseThrow(() -> ToolException.notFound("没有 " + request.app() + " 的窗口，可能已最小化"));
        return new Response(request.app(), window.app(), window.frontmost(), window,
                status.pids(), System.currentTimeMillis() - startedAt);
    }

    /**
     * @param app   目标应用名或 .app 绝对路径
     * @param title 窗口标题关键字，可空
     */
    public record Request(String app, String title) {
    }

    /**
     * @param windowOwner 窗口属主名（可能是本地化显示名，如「计算器」）
     * @param window      目标应用当前窗口
     */
    public record Response(String app, String windowOwner, boolean frontmost, WindowInfo window, List<Long> pids,
            long elapsedMs) {
    }
}
