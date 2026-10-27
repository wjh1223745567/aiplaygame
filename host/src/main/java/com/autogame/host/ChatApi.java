package com.autogame.host;

import com.autogame.client.HostClient;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * AI 对话接口（host 作为 AI 服务，供 mcpclient 的对话调试台调用）。
 *
 * <pre>
 * POST /ai/chat?session=debug   {"message":"游戏开了吗","vision":true}
 * GET  /ai/tools                工具清单（MCP listTools 原样透出）
 * GET  /ai/frame                当前画面 JPEG（客户端「卡住时看一眼」）
 * GET  /ai/history?session=…    会话历史
 * POST /ai/reset?session=…      清空上下文
 * </pre>
 */
@RestController
@RequestMapping("/ai")
public class ChatApi {

    private final AiChatService ai;
    private final FrameService frame;

    public ChatApi(AiChatService ai, FrameService frame) {
        this.ai = ai;
        this.frame = frame;
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

    /** 当前画面快照：客户端等待/疑似卡住时拉一帧看。不占工具会话，不影响正在跑的那一步。 */
    @GetMapping("/frame")
    public ResponseEntity<byte[]> frame() {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.noStore())
                    .body(frame.jpeg());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return frameError("抓帧被中断");
        } catch (Exception e) {
            return frameError(HostClient.rootMessage(e));
        }
    }

    /** 抓帧失败统一返 503 + 一句人话：客户端直接把它显示在画面标题下。 */
    private static ResponseEntity<byte[]> frameError(String message) {
        return ResponseEntity.status(503)
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(message.getBytes(StandardCharsets.UTF_8));
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
