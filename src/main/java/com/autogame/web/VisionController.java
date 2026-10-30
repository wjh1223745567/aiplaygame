package com.autogame.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.autogame.service.VisionService;

/**
 * 识图接口：截当前画面（或指定窗口）交给免费视觉大模型，返回文字结果。
 */
@RestController
@RequestMapping("/api/vision")
public class VisionController {

    private final VisionService visionService;

    public VisionController(VisionService visionService) {
        this.visionService = visionService;
    }

    @PostMapping("/analyze")
    public VisionService.VisionResult analyze(@RequestBody(required = false) AnalyzeRequest request) {
        AnalyzeRequest req = request == null ? new AnalyzeRequest(null, null, null, null, null) : request;
        return visionService.analyze(req.prompt(), req.target(), req.app(), req.title(), req.imageBase64());
    }

    /**
     * @param prompt      要模型回答的问题，必填
     * @param target      screen / window，默认 screen
     * @param app         当 target=window 时按进程名匹配
     * @param title       当 target=window 时按标题匹配
     * @param imageBase64 可选：直接传图（data URL 或纯 Base64），传了就不再截图
     */
    public record AnalyzeRequest(String prompt, String target, String app, String title, String imageBase64) {
    }
}
