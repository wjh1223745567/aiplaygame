package com.autogame.game.wendao;

import org.springframework.stereotype.Component;

import com.autogame.aop.RequiresApp;
import com.autogame.service.function.AppFunction;

/**
 * 功能：登录问道（已在游戏里则跳过）。
 *
 * <pre>
 * POST /api/function/wendaoLogin   {}
 * </pre>
 *
 * <p>账号密码来自配置，不进请求体：调用方（脚本或大模型）不该看到密码。
 * 注解里的 {@code ${...}} 是配置占位符，切面会先解析再执行「启动 → 置顶 → 接管检测」。
 */
@Component
public class WendaoLoginFunction implements AppFunction<WendaoLoginFunction.Request, WendaoLogin.Outcome> {

    private final WendaoLogin login;

    public WendaoLoginFunction(WendaoLogin login) {
        this.login = login;
    }

    @Override
    public String name() {
        return "wendaoLogin";
    }

    @Override
    public Class<Request> requestType() {
        return Request.class;
    }

    @Override
    @RequiresApp(app = "${autogame.game.wendao.app}", title = "${autogame.game.wendao.title}",
            windowTimeout = WendaoProperties.LAUNCH_WINDOW_TIMEOUT_MS)
    public WendaoLogin.Outcome execute(Request request) {
        return login.login();
    }

    /** 没有参数：账号、密码、界面坐标都在配置里。 */
    public record Request() {
    }
}
