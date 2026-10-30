package com.autogame.platform;

import java.awt.Rectangle;

/**
 * 一次截图的产物。
 *
 * @param png          PNG 字节
 * @param pixelWidth   图片实际像素宽（Retina 屏是逻辑宽的 2 倍）
 * @param pixelHeight  图片实际像素高
 * @param bounds       截图覆盖的逻辑坐标区域
 * @param source       来源描述，如 "screen" 或 "window:问道 - 主界面"
 */
public record CapturedImage(byte[] png, int pixelWidth, int pixelHeight, Rectangle bounds, String source) {
}
