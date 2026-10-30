package com.autogame.service.operation;

import static com.autogame.service.operation.OperationSupport.isBlank;
import static com.autogame.service.operation.OperationSupport.require;

import java.util.List;

import org.springframework.stereotype.Component;

import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;

/** 查询运行状态：进程号 + 当前桌面上属于它的窗口。 */
@Component
public class StatusOperation implements AppOperation<StatusOperation.Request, StatusOperation.Response> {

    private final AppService appService;

    public StatusOperation(AppService appService) {
        this.appService = appService;
    }

    @Override
    public String action() {
        return "status";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    public Response execute(Request request) {
        require(request != null, "请求体不能为空");
        require(!isBlank(request.app()) || request.pid() != null, "status 需要 app 或 pid");
        long startedAt = System.currentTimeMillis();
        AppService.StatusResult result = appService.status(request.app(), request.pid(), request.title());
        return new Response(request.app(), result.running(), result.pids(), result.windows(),
                System.currentTimeMillis() - startedAt, result.running() ? "运行中" : "未运行");
    }

    /**
     * @param app   应用名，可空
     * @param pid   精确进程号，可空
     * @param title 窗口标题关键字，用于只关心某个窗口时过滤，可空
     */
    public record Request(String app, Long pid, String title) {
    }

    /**
     * @param running 是否存在进程或窗口
     * @param windows 当前桌面上属于该应用的窗口
     */
    public record Response(String app, boolean running, List<Long> pids, List<WindowInfo> windows,
            long elapsedMs, String message) {
    }
}
