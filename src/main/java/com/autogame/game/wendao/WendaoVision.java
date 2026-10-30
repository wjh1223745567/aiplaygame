package com.autogame.game.wendao;

import java.awt.Rectangle;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.autogame.platform.CapturedImage;
import com.autogame.service.ScreenService;
import com.autogame.service.VisionService;

/**
 * 问道的页面检测：截一张游戏窗口的图，问视觉模型「这是什么页面、要点哪里」，
 * 要点的位置用截图的精确像素坐标给出。
 *
 * <p>这个类不动鼠标——点位只是识别结果的一部分，给调用方参考，点不点由人决定。
 *
 * <p>顺序刻意是「先截图，再拼提示词」：只有先拿到图片的真实像素尺寸，才能把它写进提示词，
 * 让模型按这张图的像素坐标系回报位置。反过来先拼提示词再截图，模型不知道图多大，
 * 只能给归一化坐标或瞎猜一个数。
 *
 * <p>提示词也不在这里，全部收在 {@link WendaoStage#prompt(int, int)}：
 * 每个阶段只写「当前页面 + 需要执行的动作」两段，调识别效果只用改那个文件。
 */
@Service
public class WendaoVision {

    private static final Logger log = LoggerFactory.getLogger(WendaoVision.class);

    private final VisionService visionService;
    private final ScreenService screenService;
    private final WendaoProperties config;

    public WendaoVision(VisionService visionService, ScreenService screenService, WendaoProperties config) {
        this.visionService = visionService;
        this.screenService = screenService;
        this.config = config;
    }

    /**
     * 看一眼游戏窗口，判断当前是什么页面、要点哪里。
     *
     * <p>截图按窗口号抓（{@code screencapture -l}），被其他窗口挡住也能拍到内容，
     * 所以调用前不需要把游戏置顶。
     *
     * <p>提示词和模型原文都会进日志，排查识别效果时直接看日志就行，不用另开抓包。
     *
     * @return 页面 + 动作 + 像素点位 + 模型原文
     */
    public Reading detect() {
        CapturedImage image = screenService.captureWindow(config.app(), config.title());
        String prompt = WendaoStage.prompt(image.pixelWidth(), image.pixelHeight());
        log.info("识图提示词 ↓↓↓（截图 {}x{} 像素，来源 {}）\n{}",
                image.pixelWidth(), image.pixelHeight(), image.source(), prompt);

        VisionService.VisionResult result = visionService.analyze(prompt, null, null, null,
                Base64.getEncoder().encodeToString(image.png()));
        log.info("模型返回 ↓↓↓（{}，{}ms）{}", result.model(), result.elapsedMs(), result.content());

        WendaoAnswer answer = WendaoAnswer.parse(result.content(), image.pixelWidth(), image.pixelHeight());
        Pixel spot = answer.clickable() ? new Pixel(answer.x(), answer.y()) : null;
        Pixel screenPoint = toScreen(image, spot);
        if (answer.stage() == WendaoStage.UNKNOWN) {
            log.warn("没认出页面，原文：{}", trim(result.content()));
        } else if (spot == null && answer.stage() == WendaoStage.DIALOG) {
            log.warn("认出是 {} 但模型没给点位，提示词里的坐标约定可能要调", answer.stage());
        } else {
            log.info("页面检测：{}（动作：{}）截图像素 {} → 屏幕 {}", answer.stage(), answer.stage().action(),
                    spot == null ? "无" : spot, screenPoint == null ? "无" : screenPoint);
        }
        return new Reading(answer.stage(), answer.stage().describe(), answer.stage().action(), spot, screenPoint,
                result.content(), result.model(), image.source(), image.pixelWidth(), image.pixelHeight(),
                result.elapsedMs());
    }

    /** 截图像素 → 屏幕绝对坐标：Retina 下截图是逻辑尺寸的两倍，按窗口实际宽高缩放回去。 */
    private static Pixel toScreen(CapturedImage image, Pixel spot) {
        if (spot == null) {
            return null;
        }
        Rectangle bounds = image.bounds();
        if (image.pixelWidth() <= 0 || image.pixelHeight() <= 0) {
            return spot;
        }
        double scaleX = bounds.width / (double) image.pixelWidth();
        double scaleY = bounds.height / (double) image.pixelHeight();
        return new Pixel(bounds.x + (int) Math.round(spot.x() * scaleX), bounds.y + (int) Math.round(spot.y() * scaleY));
    }

    /**
     * @param stage       判定结果；认不出来是 {@link WendaoStage#UNKNOWN}
     * @param describe    当前页面说明
     * @param action      这个页面需要执行什么动作（只告知，本服务不去点）
     * @param spot        点击点位在截图里的像素坐标；模型没给就是 null
     * @param screenPoint 同一个点换算到屏幕的绝对坐标，可直接喂 {@code POST /api/mouse/click}
     * @param answer      模型原文（调提示词时最需要的就是这个）
     * @param model       实际命中的模型（主模型被限流时会是备用模型）
     * @param source      图片来源，例如 {@code window:问道 - 问道}
     * @param pixelWidth  截图像素宽，用来说明 spot 的坐标系
     * @param pixelHeight 截图像素高
     * @param elapsedMs   模型调用耗时
     */
    public record Reading(WendaoStage stage, String describe, String action, Pixel spot, Pixel screenPoint,
            String answer, String model, String source, int pixelWidth, int pixelHeight, long elapsedMs) {
    }

    /** 一个整数坐标点。{@code spot} 用截图坐标系，{@code screenPoint} 用屏幕坐标系。 */
    public record Pixel(int x, int y) {
    }

    /** 日志里只留一行、最多 40 字，免得模型长篇大论把日志刷爆。 */
    static String trim(String content) {
        String single = content == null ? "" : content.strip().replaceAll("\\s+", " ");
        return single.length() <= 40 ? single : single.substring(0, 40) + "...";
    }
}
