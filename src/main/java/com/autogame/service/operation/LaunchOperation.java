package com.autogame.service.operation;

import static com.autogame.service.operation.OperationSupport.isBlank;
import static com.autogame.service.operation.OperationSupport.millis;
import static com.autogame.service.operation.OperationSupport.require;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;

/** 启动应用；给了 waitWindowMs 就顺便等到窗口出现，直接拿到坐标。 */
@Component
public class LaunchOperation implements AppOperation<LaunchOperation.Request, LaunchOperation.Response> {

    private static final long DEFAULT_WAIT_WINDOW_MS = 8_000;
    private static final long MAX_WAIT_WINDOW_MS = 120_000;
    private static final long MAX_WAIT_MS = 60_000;

    private final AppService appService;

    public LaunchOperation(AppService appService) {
        this.appService = appService;
    }

    @Override
    public String action() {
        return "launch";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    public Response execute(Request request) {
        require(request != null, "请求体不能为空");
        require(!isBlank(request.app()), "app 不能为空：应用名（如 Calculator）或 .app 绝对路径");
        long startedAt = System.currentTimeMillis();
        Duration windowTimeout = millis(request.waitWindowMs(), DEFAULT_WAIT_WINDOW_MS, MAX_WAIT_WINDOW_MS);
        WindowInfo window = appService.launchAndWait(request.app(), request.args(),
                millis(request.waitMs(), 0, MAX_WAIT_MS), request.title(), windowTimeout);
        String message = window != null
                ? "启动完成，窗口已就绪"
                : windowTimeout.isZero() ? "已发起启动（按参数不等待窗口）"
                        : "已发起启动，" + windowTimeout.toMillis() + "ms 内没等到窗口（用 status 或 windows 确认）";
        return new Response(request.app(), window, System.currentTimeMillis() - startedAt, message);
    }

    /**
     * @param app          应用名或 .app 绝对路径
     * @param args         传给应用的启动参数
     * @param waitMs       启动后固定等待毫秒，默认 0
     * @param waitWindowMs 等窗口出现的最长毫秒，默认 8000，0 表示不等
     * @param title        在同一应用的多个窗口里筛目标，可空
     */
    public record Request(String app, List<String> args, Long waitMs, Long waitWindowMs, String title) {
    }

    /**
     * @param window 等到的窗口；没等到为 null
     */
    public record Response(String app, WindowInfo window, long elapsedMs, String message) {
    }
}
