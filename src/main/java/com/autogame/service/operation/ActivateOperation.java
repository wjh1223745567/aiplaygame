package com.autogame.service.operation;

import static com.autogame.service.operation.OperationSupport.isBlank;
import static com.autogame.service.operation.OperationSupport.require;

import org.springframework.stereotype.Component;

import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;
import com.autogame.support.ToolException;

/** 把应用切到顶层；app / pid / title 三者至少给一个。 */
@Component
public class ActivateOperation implements AppOperation<ActivateOperation.Request, ActivateOperation.Response> {

    private final AppService appService;

    public ActivateOperation(AppService appService) {
        this.appService = appService;
    }

    @Override
    public String action() {
        return "activate";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    public Response execute(Request request) {
        require(request != null, "请求体不能为空");
        require(!isBlank(request.app()) || request.pid() != null || !isBlank(request.title()),
                "activate 需要 app、pid、title 之一");
        long startedAt = System.currentTimeMillis();
        WindowInfo window = appService.activate(request.app(), request.pid(), request.title());
        if (window == null) {
            throw ToolException.notFound("没有匹配的窗口可置顶：app=" + request.app() + " pid=" + request.pid()
                    + " title=" + request.title());
        }
        return new Response(window.app(), window, System.currentTimeMillis() - startedAt);
    }

    /**
     * @param app   应用名，可空
     * @param pid   精确进程号，可空
     * @param title 窗口标题关键字，可空
     */
    public record Request(String app, Long pid, String title) {
    }

    /**
     * @param app    命中窗口的属主名（可能是本地化显示名）
     * @param window 置顶后的窗口
     */
    public record Response(String app, WindowInfo window, long elapsedMs) {
    }
}
