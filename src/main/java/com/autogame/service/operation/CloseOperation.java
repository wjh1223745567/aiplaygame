package com.autogame.service.operation;

import static com.autogame.service.operation.OperationSupport.isBlank;
import static com.autogame.service.operation.OperationSupport.millis;
import static com.autogame.service.operation.OperationSupport.require;

import java.util.List;

import org.springframework.stereotype.Component;

import com.autogame.service.AppService;

/** 结束应用：先请它自己退，超过 graceMs 还在就强杀。 */
@Component
public class CloseOperation implements AppOperation<CloseOperation.Request, CloseOperation.Response> {

    private static final long DEFAULT_GRACE_MS = 3_000;
    private static final long MAX_GRACE_MS = 60_000;

    private final AppService appService;

    public CloseOperation(AppService appService) {
        this.appService = appService;
    }

    @Override
    public String action() {
        return "close";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    public Response execute(Request request) {
        require(request != null, "请求体不能为空");
        require(!isBlank(request.app()) || request.pid() != null, "close 需要 app 或 pid");
        long startedAt = System.currentTimeMillis();
        AppService.CloseResult result = appService.close(request.app(), request.pid(),
                millis(request.graceMs(), DEFAULT_GRACE_MS, MAX_GRACE_MS));
        boolean done = result.stillRunning().isEmpty();
        String message = done ? "已全部退出" : "仍有进程未退出：" + result.stillRunning();
        return new Response(request.app(), result.targets(), result.closed(), result.stillRunning(),
                System.currentTimeMillis() - startedAt, message);
    }

    /**
     * @param app     应用名，可空（有 pid 时可不给）
     * @param pid     精确进程号，可空
     * @param graceMs 留给应用自行退出的时间，默认 3000，超时强杀
     */
    public record Request(String app, Long pid, Long graceMs) {
    }

    /**
     * @param targets      本次尝试关闭的进程
     * @param closed       已确认退出
     * @param stillRunning 仍在运行（强杀也没结束）
     */
    public record Response(String app, List<Long> targets, List<Long> closed, List<Long> stillRunning,
            long elapsedMs, String message) {
    }
}
