package com.autogame.game.wendao;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型的一次回答：当前页面 + 要点一下的像素位置。
 *
 * <p>坐标约定：{@code x}/{@code y} 是截图里的整数像素，原点在左上角。越界或非数字一律当「没给点位」
 * （返回 null）而不是硬夹到边缘 —— 夹出来的点会点到窗口边框上，不如不给。
 *
 * <p>解析失败不抛异常：模型偶尔会输出带解释的文本，这时页面照样能认，只是没有点位。
 */
public record WendaoAnswer(WendaoStage stage, Integer x, Integer y) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public boolean clickable() {
        return x != null && y != null;
    }

    /**
     * @param answer      模型原文
     * @param pixelWidth  截图像素宽，用来判断点位是否落在图内
     * @param pixelHeight 截图像素高
     */
    public static WendaoAnswer parse(String answer, int pixelWidth, int pixelHeight) {
        if (answer == null || answer.isBlank()) {
            return new WendaoAnswer(WendaoStage.UNKNOWN, null, null);
        }
        int from = answer.indexOf('{');
        int to = answer.lastIndexOf('}');
        if (from < 0 || to <= from) {
            // 模型没按 JSON 回，只当纯文本认页面
            return new WendaoAnswer(WendaoStage.parse(answer), null, null);
        }
        try {
            JsonNode node = MAPPER.readTree(answer.substring(from, to + 1));
            WendaoStage stage = WendaoStage.parse(stageText(node));
            if (stage == WendaoStage.UNKNOWN) {
                // stage 字段缺失或写成中文以外的东西时，退回整段文本匹配
                stage = WendaoStage.parse(answer);
            }
            return new WendaoAnswer(stage, pixel(node.get("x"), pixelWidth), pixel(node.get("y"), pixelHeight));
        } catch (RuntimeException | LinkageError e) {
            return new WendaoAnswer(WendaoStage.parse(answer), null, null);
        }
    }

    /** stage 可能在 {@code stage} 里，也可能被模型写成 {@code 页面}；都没有就交给上层按文本认。 */
    private static String stageText(JsonNode node) {
        JsonNode stage = node.get("stage");
        if (stage == null || stage.isNull()) {
            stage = node.get("页面");
        }
        return stage == null || !stage.isString() ? null : stage.asString();
    }

    /** 只认图内的整数点；数字以字符串形式给出（"786"）也收。 */
    private static Integer pixel(JsonNode node, int limit) {
        if (node == null || node.isNull()) {
            return null;
        }
        int value;
        if (node.isNumber()) {
            value = node.asInt();
        } else if (node.isString() && node.asString().strip().matches("\\d+")) {
            value = Integer.parseInt(node.asString().strip());
        } else {
            return null;
        }
        return value < 0 || value >= limit ? null : value;
    }
}
