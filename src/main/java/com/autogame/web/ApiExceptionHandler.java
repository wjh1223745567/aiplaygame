package com.autogame.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.autogame.support.ToolException;

/**
 * 统一错误结构：{ok:false, status, error}，让调用方（脚本或大模型）能直接读懂失败原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ToolException.class)
    public ResponseEntity<ErrorBody> handleToolException(ToolException e) {
        log.warn("接口返回 {}：{}", e.status(), e.getMessage());
        return ResponseEntity.status(e.status()).body(new ErrorBody(false, e.status(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorBody> handleBadRequest(Exception e) {
        String message = "请求参数有误：" + e.getMessage();
        log.warn(message);
        return ResponseEntity.badRequest().body(new ErrorBody(false, 400, message));
    }

    /** 路径不存在必须回 404，不能因为兜底 handler 而成了 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(NoResourceFoundException e) {
        String message = "接口不存在：" + e.getResourcePath();
        log.warn(message);
        return ResponseEntity.status(404).body(new ErrorBody(false, 404, message));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        String message = "不支持的请求方法：" + e.getMethod();
        log.warn(message);
        return ResponseEntity.status(405).body(new ErrorBody(false, 405, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        String message = "服务内部错误：" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " - " + e.getMessage());
        return ResponseEntity.internalServerError().body(new ErrorBody(false, 500, message));
    }

    public record ErrorBody(boolean ok, int status, String error) {
    }
}
