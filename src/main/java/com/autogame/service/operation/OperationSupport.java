package com.autogame.service.operation;

import java.time.Duration;

import com.autogame.support.ToolException;

/** 各实现共用的参数校验与取值工具。 */
final class OperationSupport {

    private OperationSupport() {
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw ToolException.badRequest(message);
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** null 取默认值，并夹在 [0, max] 毫秒之间。 */
    static Duration millis(Long value, long defaultValue, long max) {
        long raw = value == null ? defaultValue : value;
        return Duration.ofMillis(Math.min(max, Math.max(0, raw)));
    }
}
