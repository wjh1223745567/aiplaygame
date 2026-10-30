package com.autogame.support;

/**
 * 面向接口的错误：带 HTTP 状态码，由全局异常处理器统一转成 JSON。
 */
public class ToolException extends RuntimeException {

    private final int status;

    public ToolException(int status, String message) {
        super(message);
        this.status = status;
    }

    public ToolException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static ToolException badRequest(String message) {
        return new ToolException(400, message);
    }

    public static ToolException notFound(String message) {
        return new ToolException(404, message);
    }

    /** 423：目标被占用需要等待（如鼠标正被人工操作或远程控制接管），调用方应暂停后重试。 */
    public static ToolException locked(String message) {
        return new ToolException(423, message);
    }

    public static ToolException internal(String message) {
        return new ToolException(500, message);
    }

    public static ToolException internal(String message, Throwable cause) {
        return new ToolException(500, message, cause);
    }
}
