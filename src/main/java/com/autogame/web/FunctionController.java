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

import com.autogame.service.function.AppFunction;
import com.autogame.support.ToolException;

/**
 * 应用功能统一入口：所有功能共用 {@link AppFunction} 接口，一项功能一个实现类，
 * 这里只负责按路径里的功能名找到实现并交给它执行。
 *
 * <p>「启动 → 置顶 → 接管检测」不在这里做，由实现类上的
 * {@link com.autogame.aop.RequiresApp} 注解交给切面，控制器保持纯路由。
 *
 * <pre>
 * GET  /api/function                列出可用功能
 * POST /api/function/calculator     {"a":5,"b":5,"op":"add"}
 * </pre>
 */
@RestController
@RequestMapping("/api/function")
public class FunctionController {

    private static final Logger log = LoggerFactory.getLogger(FunctionController.class);

    private final Map<String, AppFunction<?, ?>> functions;
    private final RequestConverter requestConverter;

    public FunctionController(List<AppFunction<?, ?>> functions, RequestConverter requestConverter) {
        this.functions = functions.stream()
                .collect(Collectors.toUnmodifiableMap(function -> function.name().toLowerCase(Locale.ROOT),
                        Function.identity()));
        this.requestConverter = requestConverter;
        log.info("已加载应用功能实现：{}", names());
    }

    /** 列出可用功能，方便调用方（脚本/大模型）自行发现能力。 */
    @GetMapping
    public Map<String, Object> names() {
        return Map.of("functions", functions.keySet().stream().sorted().toList());
    }

    /** 按功能名分发；请求体与响应体都由对应实现类决定。 */
    @PostMapping("/{name}")
    public Object perform(@PathVariable String name, @RequestBody(required = false) Map<String, Object> body) {
        AppFunction<?, ?> function = functions.get(name.toLowerCase(Locale.ROOT));
        if (function == null) {
            throw ToolException.badRequest("不支持的功能：" + name
                    + "，可用：" + String.join("/", functions.keySet().stream().sorted().toList()));
        }
        return execute(function, body);
    }

    private <REQ, RES> RES execute(AppFunction<REQ, RES> function, Map<String, Object> body) {
        return function.execute(requestConverter.bind(body, function.requestType(), function.name()));
    }
}
