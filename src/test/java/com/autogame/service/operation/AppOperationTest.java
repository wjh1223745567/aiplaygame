package com.autogame.service.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.autogame.support.ToolException;

/**
 * 只验证各实现的动作名、请求体类型与参数校验；跑真实平台的能力留给接口联调。
 */
class AppOperationTest {

    private final List<AppOperation<?, ?>> operations = List.of(
            new LaunchOperation(null),
            new ActivateOperation(null),
            new CloseOperation(null),
            new RestartOperation(null),
            new StatusOperation(null));

    @Test
    void 每个动作一个实现且动作名不重复() {
        List<String> actions = operations.stream().map(AppOperation::action).sorted().toList();
        assertEquals(List.of("activate", "close", "launch", "restart", "status"), actions);
    }

    @Test
    void 请求体类型就是实现自带的Request() {
        assertSame(LaunchOperation.Request.class, new LaunchOperation(null).requestType());
        assertSame(ActivateOperation.Request.class, new ActivateOperation(null).requestType());
        assertSame(CloseOperation.Request.class, new CloseOperation(null).requestType());
        assertSame(RestartOperation.Request.class, new RestartOperation(null).requestType());
        assertSame(StatusOperation.Request.class, new StatusOperation(null).requestType());
    }

    @Test
    void launch缺app直接报400() {
        ToolException error = assertThrows(ToolException.class, () -> new LaunchOperation(null)
                .execute(new LaunchOperation.Request(null, null, null, null, null)));
        assertEquals(400, error.status());
        assertTrue(error.getMessage().contains("app 不能为空"));
    }

    @Test
    void close缺app和pid直接报400() {
        ToolException error = assertThrows(ToolException.class,
                () -> new CloseOperation(null).execute(new CloseOperation.Request(null, null, null)));
        assertEquals(400, error.status());
    }

    @Test
    void status缺app和pid直接报400() {
        ToolException error = assertThrows(ToolException.class,
                () -> new StatusOperation(null).execute(new StatusOperation.Request(null, null, null)));
        assertEquals(400, error.status());
    }

    @Test
    void activate三个条件都不给直接报400() {
        ToolException error = assertThrows(ToolException.class,
                () -> new ActivateOperation(null).execute(new ActivateOperation.Request(null, null, " ")));
        assertEquals(400, error.status());
    }
}
