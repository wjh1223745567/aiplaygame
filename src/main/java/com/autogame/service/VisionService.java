package com.autogame.service;

import java.util.Base64;

import org.springframework.stereotype.Service;

import com.autogame.config.AutoGameProperties;
import com.autogame.llm.VisionLlmClient;
import com.autogame.platform.CapturedImage;
import com.autogame.support.ToolException;

/**
 * 识图编排：取图（截图或调用方直传）→ 校验体积 → 交给视觉大模型。
 */
@Service
public class VisionService {

    private final ScreenService screenService;
    private final VisionLlmClient llmClient;
    private final AutoGameProperties.Vision config;

    public VisionService(ScreenService screenService, VisionLlmClient llmClient, AutoGameProperties properties) {
        this.screenService = screenService;
        this.llmClient = llmClient;
        this.config = properties.vision();
    }

    public VisionResult analyze(String prompt, String target, String app, String title, String imageBase64) {
        if (prompt == null || prompt.isBlank()) {
            throw ToolException.badRequest("prompt 不能为空：描述你要模型看图回答什么");
        }
        byte[] png;
        String source;
        int pixelWidth = 0;
        int pixelHeight = 0;
        if (imageBase64 != null && !imageBase64.isBlank()) {
            png = decode(imageBase64);
            source = "base64";
        } else {
            CapturedImage image = screenService.capture(target, app, title);
            png = image.png();
            source = image.source();
            pixelWidth = image.pixelWidth();
            pixelHeight = image.pixelHeight();
        }
        long maxBytes = config.maxImageBytes().toBytes();
        if (png.length > maxBytes) {
            throw ToolException.badRequest("图片过大：" + png.length + " 字节，上限 " + maxBytes
                    + " 字节；请缩小截图区域（target=window）或先自行压缩");
        }
        long startedAt = System.currentTimeMillis();
        VisionLlmClient.Answer answer = llmClient.analyze(png, prompt);
        long elapsed = System.currentTimeMillis() - startedAt;
        return new VisionResult(answer.content(), answer.model(), source, png.length, pixelWidth, pixelHeight, elapsed);
    }

    private byte[] decode(String imageBase64) {
        String payload = imageBase64.strip();
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }
        try {
            return Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw ToolException.badRequest("imageBase64 不是合法的 Base64 内容：" + e.getMessage());
        }
    }

    /**
     * @param content     模型输出（已清除 think 段）
     * @param model       实际命中的模型
     * @param source      图片来源：screen / window:xxx / base64
     * @param imageBytes  图片字节数
     * @param pixelWidth  图片像素宽（base64 直传时为 0）
     * @param pixelHeight 图片像素高（base64 直传时为 0）
     * @param elapsedMs   模型调用耗时
     */
    public record VisionResult(
            String content,
            String model,
            String source,
            int imageBytes,
            int pixelWidth,
            int pixelHeight,
            long elapsedMs) {
    }
}
