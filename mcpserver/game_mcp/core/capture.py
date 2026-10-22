"""屏幕/窗口截图。

优先使用 CGWindowListCreateImage 按 window_id 抓取（可捕获被遮挡窗口），
失败时回退到 mss 抓屏幕区域。需要系统「屏幕录制」权限。
"""
from __future__ import annotations

import os
import time
from typing import Optional

import cv2
import numpy as np
import Quartz

from .window import WindowInfo


class CaptureError(RuntimeError):
    pass


def _cgimage_to_bgr(cg_image) -> Optional[np.ndarray]:
    """CGImageRef -> BGR numpy 数组。"""
    w = Quartz.CGImageGetWidth(cg_image)
    h = Quartz.CGImageGetHeight(cg_image)
    if not w or not h:
        return None
    cs = Quartz.CGColorSpaceCreateDeviceRGB()
    # BGRA 内存布局，与 cv2 直接兼容
    bmp = Quartz.CGBitmapContextCreate(
        None, w, h, 8, w * 4, cs,
        Quartz.kCGImageAlphaPremultipliedLast | Quartz.kCGBitmapByteOrder32Big,
    )
    Quartz.CGContextDrawImage(bmp, Quartz.CGRectMake(0, 0, w, h), cg_image)
    data_provider = Quartz.CGBitmapContextCreateImage(bmp)
    length = Quartz.CGDataProviderCopyData(Quartz.CGImageGetDataProvider(data_provider))
    arr = np.frombuffer(length, dtype=np.uint8).reshape(h, w, 4)
    return cv2.cvtColor(arr, cv2.COLOR_RGBA2BGR)


def capture_window(win: WindowInfo) -> np.ndarray:
    """截取指定窗口，返回 BGR 图像（原生像素分辨率）。

    成功后会回填 win.scale（图像像素宽 / 窗口逻辑宽），供坐标换算使用。
    """
    img_ref = Quartz.CGWindowListCreateImage(
        Quartz.CGRectNull,
        Quartz.kCGWindowListOptionIncludingWindow,
        win.window_id,
        Quartz.kCGWindowImageBoundsIgnoreFraming | Quartz.kCGWindowImageNominalResolution,
    )
    if img_ref is not None:
        bgr = _cgimage_to_bgr(img_ref)
        if bgr is not None and bgr.size:
            win.scale = bgr.shape[1] / max(win.width, 1)
            return bgr
    # 回退：抓取屏幕区域（要求窗口在最前）
    return _capture_screen_region(win)


def _capture_screen_region(win: WindowInfo) -> np.ndarray:
    import mss
    with mss.mss() as sct:
        monitor = {"left": int(win.x), "top": int(win.y),
                   "width": int(win.width), "height": int(win.height)}
        shot = sct.grab(monitor)
        bgr = cv2.cvtColor(np.array(shot), cv2.COLOR_BGRA2BGR)
        win.scale = bgr.shape[1] / max(win.width, 1)
        return bgr


def save_image(img: np.ndarray, path: str) -> str:
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    if not cv2.imwrite(path, img):
        raise CaptureError(f"图片保存失败: {path}")
    return path


def debug_save(img: np.ndarray, tag: str, debug_dir: str = "debug") -> str:
    """保存调试截图，文件名带时间戳。"""
    ts = time.strftime("%Y%m%d_%H%M%S")
    path = os.path.join(debug_dir, f"{ts}_{tag}.png")
    return save_image(img, path)
