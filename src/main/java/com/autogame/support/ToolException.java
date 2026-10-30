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

    public static ToolException internal(String message) {
        return new ToolException(500, message);
    }

    public static ToolException internal(String message, Throwable cause) {
        return new ToolException(500, message, cause);
    }
}
