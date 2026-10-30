package com.autogame.service.function;

/**
 * 应用功能的统一接口：一项功能一个实现类，按 {@link #name()} 路由。
 *
 * <p>与 {@link com.autogame.service.operation.AppOperation} 的区别：
 * 操作管的是「应用怎么开、怎么关、怎么查」，功能管的是「应用能做什么」——
 * 例如计算、进副本、领任务这类具体能力。请求体/响应体同样由各实现自带 record 定义。
 *
 * <p>需要在桌面上操作某个应用时，在实现类的 {@code execute} 上标
 * {@code @RequiresApp(app = "#request?.app()")}，切面会先确保应用已启动、已在顶层、
 * 鼠标没被接管，再调用方法体；本接口因此不必关心前置条件。
 *
 * @param <REQ> 请求体类型
 * @param <RES> 响应体类型
 */
public interface AppFunction<REQ, RES> {

    /** 功能名，对应 POST /api/function/{name} 的路径段。 */
    String name();

    /** 请求体类型，路由层据此把 JSON 转成对应 record。 */
    Class<REQ> requestType();

    /** 执行功能；参数不合法时抛 {@link com.autogame.support.ToolException}。 */
    RES execute(REQ request);
}
