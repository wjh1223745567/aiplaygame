package com.autogame.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autogame.service.operation.AppOperation;
import com.autogame.support.ToolException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 应用操作统一入口：所有动作共用 {@link AppOperation} 接口，一个动作一个实现类，
 * 这里只负责按路径里的动作名找到实现并交给它执行，不含任何动作自己的逻辑。
 *
 * <pre>
 * GET  /api/app            列出可用动作
 * POST /api/app/launch     {"app":"Calculator","waitWindowMs":8000}
 * POST /api/app/activate   {"app":"Calculator"}
 * POST /api/app/close      {"app":"Calculator","graceMs":2000}
 * POST /api/app/restart    {"app":"Calculator"}
 * POST /api/app/status     {"app":"Calculator"}
 * </pre>
 */
@RestController
@RequestMapping("/api/app")
public class AppController {

    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    private final Map<String, AppOperation<?, ?>> operations;
    private final ObjectMapper objectMapper;

    public AppController(List<AppOperation<?, ?>> operations, ObjectMapper objectMapper) {
        this.operations = operations.stream()
                .collect(Collectors.toUnmodifiableMap(operation -> operation.action().toLowerCase(Locale.ROOT),
                        Function.identity()));
        this.objectMapper = objectMapper;
        log.info("已加载应用操作实现：{}", actions());
    }

    /** 列出可用动作，方便调用方（脚本/大模型）自行发现能力。 */
    @GetMapping
    public Map<String, Object> actions() {
        return Map.of("actions", operations.keySet().stream().sorted().toList());
    }

    /** 按动作分发；请求体与响应体都由对应实现类决定。 */
    @PostMapping("/{action}")
    public Object perform(@PathVariable String action, @RequestBody(required = false) Map<String, Object> body) {
        AppOperation<?, ?> operation = operations.get(action.toLowerCase(Locale.ROOT));
        if (operation == null) {
            throw ToolException.badRequest("不支持的动作：" + action
                    + "，可用：" + String.join("/", operations.keySet().stream().sorted().toList()));
        }
        return execute(operation, body == null ? Map.of() : body);
    }

    private <REQ, RES> RES execute(AppOperation<REQ, RES> operation, Map<String, Object> body) {
        REQ request;
        try {
            request = objectMapper.convertValue(body, operation.requestType());
        } catch (IllegalArgumentException | JacksonException e) {
            throw ToolException.badRequest(operation.action() + " 的参数格式不对：" + firstLine(e));
        }
        return operation.execute(request);
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.lines().findFirst().orElse("");
    }
}
