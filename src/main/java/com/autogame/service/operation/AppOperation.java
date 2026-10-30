package com.autogame.service.operation;

/**
 * 应用操作的统一接口：一个动作一个实现类，路由层按 {@link #action()} 找实现。
 *
 * <p>请求体与响应体由各实现自己定义（内嵌 record），互不影响；新增动作只需多写一个实现类，
 * 路由层与已有实现都不用改。
 *
 * @param <REQ> 请求体类型
 * @param <RES> 响应体类型
 */
public interface AppOperation<REQ, RES> {

    /** 动作名，对应 POST /api/app/{action} 的路径段。 */
    String action();

    /** 请求体类型，路由层据此把 JSON 转成对应 record。 */
    Class<REQ> requestType();

    /** 执行动作；参数不合法时抛 {@link com.autogame.support.ToolException}。 */
    RES execute(REQ request);
}
