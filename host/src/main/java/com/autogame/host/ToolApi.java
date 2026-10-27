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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具门面：客户端只看这三个端点，host 不解释识别结果、也不做任何决策。
 *
 * <pre>
 * GET  /ai/tools                 工具清单（MCP listTools 原样透出）
 * POST /ai/invoke?tool=detect    调用一个工具，body 就是该工具的参数 JSON
 * GET  /ai/frame                 当前画面 JPEG（给眼睛看，不当工具、不进清单）
 * </pre>
 *
 * <p>工具失败统一用 200 + {@code ok=false} 表达（与 Python 侧 dict 工具的约定一致），
 * 只有抓帧走 HTTP 状态码：客户端要区分「拿到了图」和「没拿到」。
 */
@RestController
@RequestMapping("/ai")
public class ToolApi {

    private final GameService game;
    private final FrameService frame;

    public ToolApi(GameService game, FrameService frame) {
        this.game = game;
        this.frame = frame;
    }

    /** 工具清单：客户端拿它渲染可调用的工具与参数。 */
    @GetMapping("/tools")
    public Map<String, Object> tools() {
        List<Map<String, Object>> specs = game.toolSpecs();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", specs.size());
        body.put("tools", specs);
        return body;
    }

    /** 执行一次工具调用：参数原样给 MCP，结果原样回客户端。 */
    @PostMapping("/invoke")
    public Map<String, Object> invoke(@RequestParam(name = "tool", required = false) String tool,
                                      @RequestBody(required = false) Map<String, Object> args) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        if (tool == null || tool.isBlank()) {
            body.put("ok", false);
            body.put("error", "缺少 tool 参数（POST /ai/invoke?tool=detect）");
            return body;
        }
        try {
            Object result = game.invoke(tool, args == null ? Map.of() : args);
            body.put("result", result);
            // 工具自己判定的失败（dict 里的 ok=false，如“未找到游戏窗口”）也算失败：
            // 否则客户端会把一次没识别出东西的调用标成成功。
            if (result instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok"))) {
                Object error = map.get("error");
                body.put("ok", false);
                body.put("error", error == null ? "工具返回 ok=false" : String.valueOf(error));
            } else {
                body.put("ok", true);
            }
        } catch (Exception e) {
            body.put("ok", false);
            body.put("error", HostClient.oneLine(HostClient.rootMessage(e), 500));
        }
        return body;
    }

    /** 当前画面快照：不占工具会话，也不影响正在跑的调用。 */
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
}
