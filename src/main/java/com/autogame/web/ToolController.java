package com.autogame.web;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.autogame.config.AutoGameProperties;
import com.autogame.platform.CapturedImage;
import com.autogame.platform.DesktopPlatform;
import com.autogame.platform.WindowInfo;
import com.autogame.service.AppService;
import com.autogame.service.MouseService;
import com.autogame.service.ScreenService;
import com.autogame.support.ToolException;

/**
 * 桌面操作接口：窗口枚举、截图、鼠标。应用启停见 {@link AppController}。
 */
@RestController
@RequestMapping("/api")
public class ToolController {

    private final AppService appService;
    private final ScreenService screenService;
    private final MouseService mouseService;
    private final DesktopPlatform platform;
    private final AutoGameProperties.Vision visionConfig;

    public ToolController(AppService appService, ScreenService screenService, MouseService mouseService,
            DesktopPlatform platform, AutoGameProperties properties) {
        this.appService = appService;
        this.screenService = screenService;
        this.mouseService = mouseService;
        this.platform = platform;
        this.visionConfig = properties.vision();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("platform", platform.name());
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("visionBaseUrl", visionConfig.baseUrl());
        body.put("visionPrimaryModel", visionConfig.primaryModel());
        body.put("visionFallbackModel", visionConfig.fallbackModel());
        body.put("visionApiKeyConfigured", !visionConfig.apiKey().isBlank());
        return body;
    }

    @GetMapping("/windows")
    public List<WindowInfo> windows() {
        return appService.windows();
    }

    /** 直接返回 PNG 图片，便于浏览器/脚本直接看图。 */
    @GetMapping(value = "/screenshot", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> screenshot(
            @RequestParam(defaultValue = "screen") String target,
            @RequestParam(required = false) String app,
            @RequestParam(required = false) String title,
            @RequestParam(defaultValue = "false") boolean save) {
        CapturedImage image = screenService.capture(target, app, title);
        String savedPath = save ? screenService.save(image).toString() : null;
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Capture-Source", image.source())
                .header("X-Capture-Pixel-Size", image.pixelWidth() + "x" + image.pixelHeight())
                .header("X-Capture-Bounds", image.bounds().x + "," + image.bounds().y + ","
                        + image.bounds().width + "," + image.bounds().height)
                .header("X-Capture-Saved", savedPath == null ? "" : java.net.URLEncoder.encode(savedPath,
                        java.nio.charset.StandardCharsets.UTF_8))
                .body(image.png());
    }

    /** 返回带元数据的截图（含 Base64），方便一次拿到坐标信息。 */
    @PostMapping("/screenshot")
    public ScreenshotResponse screenshot(@RequestBody(required = false) ScreenshotRequest request) {
        ScreenshotRequest req = request == null ? new ScreenshotRequest(null, null, null, null) : request;
        CapturedImage image = screenService.capture(req.target(), req.app(), req.title());
        boolean save = req.save() != null && req.save();
        String savedPath = save ? screenService.save(image).toString() : null;
        return new ScreenshotResponse(image.source(), image.pixelWidth(), image.pixelHeight(),
                new Bounds(image.bounds().x, image.bounds().y, image.bounds().width, image.bounds().height),
                java.util.Base64.getEncoder().encodeToString(image.png()), savedPath);
    }

    @PostMapping("/mouse/move")
    public PointResponse move(@RequestBody MouseMoveRequest request) {
        if (request == null || request.x() == null || request.y() == null) {
            throw ToolException.badRequest("x、y 不能为空");
        }
        var point = mouseService.moveTo(request.x(), request.y(), millis(request.durationMs(), 0, 10_000));
        return new PointResponse(point.x, point.y, mouseService.currentPosition());
    }

    @PostMapping("/mouse/click")
    public PointResponse click(@RequestBody MouseClickRequest request) {
        if (request == null || request.x() == null || request.y() == null) {
            throw ToolException.badRequest("x、y 不能为空");
        }
        int count = request.count() == null ? 1 : request.count();
        boolean moveFirst = request.moveFirst() == null || request.moveFirst();
        if (moveFirst) {
            mouseService.moveTo(request.x(), request.y(), millis(request.moveDurationMs(), 0, 10_000));
        }
        var point = mouseService.clickAt(request.x(), request.y(), request.button(), count,
                millis(request.intervalMs(), 0, 5_000));
        return new PointResponse(point.x, point.y, mouseService.currentPosition());
    }

    private static Duration millis(Long value, long min, long max) {
        if (value == null) {
            return null;
        }
        long clamped = Math.min(max, Math.max(min, value));
        return Duration.ofMillis(clamped);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 截图请求：target=screen 整屏，target=window 需 app 或 title。 */
    public record ScreenshotRequest(String target, String app, String title, Boolean save) {
    }

    public record MouseMoveRequest(Integer x, Integer y, Long durationMs) {
    }

    public record MouseClickRequest(Integer x, Integer y, String button, Integer count, Long intervalMs,
            Long moveDurationMs, Boolean moveFirst) {
    }

    public record Bounds(int x, int y, int width, int height) {
    }

    public record ScreenshotResponse(String source, int pixelWidth, int pixelHeight, Bounds bounds, String imageBase64,
            String savedPath) {
    }

    public record PointResponse(int x, int y, java.awt.Point cursor) {
    }
}
