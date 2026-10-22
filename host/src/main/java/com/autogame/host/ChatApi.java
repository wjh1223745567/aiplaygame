package com.autogame.host;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 对话接口（host 作为 AI 服务，供 mcpclient 的对话调试台调用）。
 *
 * <pre>
 * POST /ai/chat?session=debug   {"message":"游戏开了吗","vision":true}
 * GET  /ai/tools                工具清单（MCP listTools 原样透出）
 * GET  /ai/history?session=…    会话历史
 * POST /ai/reset?session=…      清空上下文
 * </pre>
 */
@RestController
@RequestMapping("/ai")
public class ChatApi {

    private final AiChatService ai;

    public ChatApi(AiChatService ai) {
        this.ai = ai;
    }

    /** 一轮对话：自然语言 -> 模型选工具 -> MCP 执行（可多步）-> 回答。 */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody(required = false) Map<String, Object> body,
                                    @RequestParam(name = "session", defaultValue = "default") String session,
                                    @RequestParam(name = "vision", required = false) Boolean vision,
                                    @RequestParam(name = "maxSteps", defaultValue = "0") int maxSteps) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        String message = payload.get("message") == null ? "" : String.valueOf(payload.get("message"));
        String sessionId = payload.get("session") == null ? session : String.valueOf(payload.get("session"));
        Boolean withVision = vision;
        if (payload.get("vision") instanceof Boolean b) {
            withVision = b;
        }
        int steps = maxSteps;
        if (payload.get("maxSteps") instanceof Number n) {
            steps = n.intValue();
        }
        return ai.chat(sessionId, message, withVision, steps);
    }

    /** 当前可用的 MCP 工具清单（调试时确认 server 暴露了哪些工具）。 */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return ai.tools();
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(name = "session", defaultValue = "default") String session) {
        return ai.history(session);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(name = "session", defaultValue = "default") String session) {
        return ai.reset(session);
    }
}
