package com.autogame.platform;

import java.util.Locale;

/**
 * 一个可见窗口的快照。坐标与尺寸都是逻辑像素（macOS 的 point），与 AWT 鼠标坐标一致。
 *
 * @param app       所属进程名，如 "问道"
 * @param title     窗口标题（未授予屏幕录制权限时可能为空）
 * @param pid       进程号
 * @param x         左上角 x
 * @param y         左上角 y
 * @param width     宽
 * @param height    高
 * @param frontmost 是否属于当前前台应用
 * @param windowId  CoreGraphics 窗口号，可用于 screencapture -l 按窗口抓图
 */
public record WindowInfo(
        String app,
        String title,
        long pid,
        int x,
        int y,
        int width,
        int height,
        boolean frontmost,
        long windowId) {

    /**
     * 按进程名、标题做包含匹配；两个条件都为空即认为匹配任意窗口。
     */
    public boolean matches(String appQuery, String titleQuery) {
        if (!containsIgnoreCase(app, appQuery)) {
            return false;
        }
        return containsIgnoreCase(title, titleQuery);
    }

    public int area() {
        return width * height;
    }

    private static boolean containsIgnoreCase(String value, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }
}
