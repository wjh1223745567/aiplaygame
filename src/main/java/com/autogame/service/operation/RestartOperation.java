package com.autogame.service.operation;

import static com.autogame.service.operation.OperationSupport.isBlank;
import static com.autogame.service.operation.OperationSupport.millis;
import static com.autogame.service.operation.OperationSupport.require;

import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;

/** 关闭后重新启动，用于客户端卡死恢复。 */
@Component
public class RestartOperation implements AppOperation<RestartOperation.Request, RestartOperation.Response> {

    private static final long DEFAULT_WAIT_WINDOW_MS = 8_000;
    private static final long MAX_WAIT_WINDOW_MS = 120_000;
    private static final long DEFAULT_GRACE_MS = 3_000;
    private static final long MAX_GRACE_MS = 60_000;
    private static final long MAX_WAIT_MS = 60_000;

    private final AppService appService;

    public RestartOperation(AppService appService) {
        this.appService = appService;
    }

    @Override
    public String action() {
        return "restart";
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
        AppService.RestartResult result = appService.restart(request.app(), request.args(),
                millis(request.waitMs(), 0, MAX_WAIT_MS), request.title(), windowTimeout,
                millis(request.graceMs(), DEFAULT_GRACE_MS, MAX_GRACE_MS));
        String message = result.window() != null
                ? "已重启，窗口已就绪"
                : "已重启，但没等到窗口（用 status 或 windows 确认）";
        return new Response(request.app(), result.previousPids(), result.window(),
                System.currentTimeMillis() - startedAt, message);
    }

    /**
     * @param app          应用名或 .app 绝对路径
     * @param args         传给应用的启动参数
     * @param waitMs       启动后固定等待毫秒，默认 0
     * @param waitWindowMs 等窗口出现的最长毫秒，默认 8000，0 表示不等
     * @param graceMs      关闭时留给应用自行退出的时间，默认 3000
     * @param title        在同一应用的多个窗口里筛目标，可空
     */
    public record Request(String app, List<String> args, Long waitMs, Long waitWindowMs, Long graceMs,
            String title) {
    }

    /**
     * @param previousPids 重启前匹配到的进程
     * @param window       重启后等到的窗口；没等到为 null
     */
    public record Response(String app, List<Long> previousPids, WindowInfo window, long elapsedMs, String message) {
    }
}
