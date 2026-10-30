package com.autogame.platform;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import com.sun.jna.platform.mac.CoreFoundation.CFArrayRef;
import com.sun.jna.platform.mac.CoreFoundation.CFDictionaryRef;
import com.sun.jna.platform.mac.CoreFoundation.CFNumberRef;
import com.sun.jna.platform.mac.CoreFoundation.CFStringRef;
import com.sun.jna.platform.mac.CoreFoundation.CFTypeRef;

/**
 * 用 CoreGraphics 的 CGWindowListCopyWindowInfo 枚举窗口。
 *
 * <p>相比 osascript/System Events 的好处：不需要「自动化」与「辅助功能」授权，
 * 只要「屏幕录制」即可拿到窗口标题与坐标；坐标是逻辑像素（point），与 AWT 鼠标坐标同一套。
 */
final class MacWindowList {

    private static final Logger log = LoggerFactory.getLogger(MacWindowList.class);

    /** 只取当前屏幕上可见的窗口，并排除桌面元素（菜单栏、Dock 图标等）。 */
    private static final int OPTION_ON_SCREEN_ONLY = 1 << 0;
    private static final int OPTION_EXCLUDE_DESKTOP_ELEMENTS = 1 << 4;
    private static final int NULL_WINDOW_ID = 0;
    /** 普通应用窗口的 layer 是 0，其余（菜单栏、悬浮面板）跳过。 */
    private static final int NORMAL_WINDOW_LAYER = 0;
    private static final int MIN_SIDE = 40;

    private static final CFStringRef KEY_OWNER = CFStringRef.createCFString("kCGWindowOwnerName");
    private static final CFStringRef KEY_NAME = CFStringRef.createCFString("kCGWindowName");
    private static final CFStringRef KEY_NUMBER = CFStringRef.createCFString("kCGWindowNumber");
    private static final CFStringRef KEY_PID = CFStringRef.createCFString("kCGWindowOwnerPID");
    private static final CFStringRef KEY_LAYER = CFStringRef.createCFString("kCGWindowLayer");
    private static final CFStringRef KEY_BOUNDS = CFStringRef.createCFString("kCGWindowBounds");
    private static final CFStringRef KEY_X = CFStringRef.createCFString("X");
    private static final CFStringRef KEY_Y = CFStringRef.createCFString("Y");
    private static final CFStringRef KEY_WIDTH = CFStringRef.createCFString("Width");
    private static final CFStringRef KEY_HEIGHT = CFStringRef.createCFString("Height");

    private MacWindowList() {
    }

    interface CoreGraphics extends Library {
        CoreGraphics INSTANCE = Native.load("CoreGraphics", CoreGraphics.class);

        Pointer CGWindowListCopyWindowInfo(int option, int relativeToWindow);
    }

    /**
     * @param includeOffscreen true 时连最小化、其他桌面的窗口一并返回（用于判断/激活“是否已打开”），
     *                         此时无法可靠判定前台应用，frontmost 一律为 false
     */
    static List<WindowInfo> list(boolean includeOffscreen) {
        int option = OPTION_EXCLUDE_DESKTOP_ELEMENTS | (includeOffscreen ? 0 : OPTION_ON_SCREEN_ONLY);
        Pointer arrayPointer;
        try {
            arrayPointer = CoreGraphics.INSTANCE.CGWindowListCopyWindowInfo(option, NULL_WINDOW_ID);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("当前系统不支持 CoreGraphics 窗口枚举：" + e.getMessage(), e);
        }
        if (arrayPointer == null) {
            return List.of();
        }
        boolean markFrontmost = !includeOffscreen;
        CFArrayRef array = new CFArrayRef(arrayPointer);
        try {
            int count = array.getCount();
            List<WindowInfo> windows = new ArrayList<>(count);
            String frontOwner = null;
            for (int i = 0; i < count; i++) {
                Pointer dictPointer = array.getValueAtIndex(i);
                if (dictPointer == null) {
                    continue;
                }
                CFDictionaryRef dict = new CFDictionaryRef(dictPointer);
                int layer = (int) number(dict, KEY_LAYER);
                if (layer != NORMAL_WINDOW_LAYER) {
                    continue;
                }
                String owner = text(dict, KEY_OWNER);
                if (markFrontmost && frontOwner == null) {
                    frontOwner = owner;
                }
                int x = (int) Math.round(bounds(dict, KEY_X));
                int y = (int) Math.round(bounds(dict, KEY_Y));
                int width = (int) Math.round(bounds(dict, KEY_WIDTH));
                int height = (int) Math.round(bounds(dict, KEY_HEIGHT));
                if (width < MIN_SIDE || height < MIN_SIDE) {
                    continue;
                }
                windows.add(new WindowInfo(
                        owner,
                        text(dict, KEY_NAME),
                        number(dict, KEY_PID),
                        x,
                        y,
                        width,
                        height,
                        owner.equals(frontOwner),
                        number(dict, KEY_NUMBER)));
            }
            return windows;
        } finally {
            CoreFoundation.INSTANCE.CFRelease(new CFTypeRef(arrayPointer));
        }
    }

    private static String text(CFDictionaryRef dict, CFStringRef key) {
        Pointer value = dict.getValue(key);
        return value == null ? "" : new CFStringRef(value).stringValue();
    }

    private static long number(CFDictionaryRef dict, CFStringRef key) {
        Pointer value = dict.getValue(key);
        return value == null ? 0L : new CFNumberRef(value).longValue();
    }

    private static double bounds(CFDictionaryRef dict, CFStringRef key) {
        Pointer value = dict.getValue(KEY_BOUNDS);
        if (value == null) {
            return 0;
        }
        Pointer field = new CFDictionaryRef(value).getValue(key);
        if (field == null) {
            log.debug("窗口 bounds 缺少字段：{}", key.stringValue());
            return 0;
        }
        return new CFNumberRef(field).doubleValue();
    }
}
