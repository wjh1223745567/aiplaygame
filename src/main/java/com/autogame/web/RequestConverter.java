package com.autogame.web;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.autogame.support.ToolException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 把请求体 JSON（已解析成 Map）转成各实现类自带的 Request record。
 *
 * <p>动作/功能路由都共用它，避免每个 controller 重复写一遍转换与报错。
 */
@Component
public class RequestConverter {

    private final ObjectMapper objectMapper;

    public RequestConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param body   请求体，null 视为空对象
     * @param type   目标 record 类型
     * @param action 动作/功能名，仅用于拼错误信息
     */
    public <REQ> REQ bind(Map<String, Object> body, Class<REQ> type, String action) {
        try {
            return objectMapper.convertValue(body == null ? Map.of() : body, type);
        } catch (IllegalArgumentException | JacksonException e) {
            throw ToolException.badRequest(action + " 的参数格式不对：" + firstLine(e));
        }
    }

    private static String firstLine(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.lines().findFirst().orElse("");
    }
}
