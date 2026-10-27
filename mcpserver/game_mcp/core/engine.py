"""基础能力统一上下文：窗口 / 截图 / OCR / 通用检测一站式入口。

只承载「看」的能力：抓一帧、读文字、找实体、把两者融合成区域清单。
点击、按键、业务流都已被移除，需要动作时再往这一层加，不用改工具入口。
"""
from __future__ import annotations

import time
from typing import List, Optional

import numpy as np

from ..core import capture as cap
from ..core.window import WindowInfo
from ..vision.ocr import OcrResult, ocr_image
from ..vision.screen import Region, analyze_regions
from ..vision.yolo import YoloDetector
from .log import get_logger

_log = get_logger("core.engine")


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


class BotContext:
    """基础能力上下文：截图、OCR、通用检测。"""

    def __init__(self, cfg: dict, win: WindowInfo):
        self.cfg = cfg
        self.win = win
        self.general = YoloDetector(cfg.get("general", {}))
        self.ocr_min_conf = cfg.get("ocr", {}).get("min_confidence", 0.5)
        self.debug_dir = cfg.get("capture", {}).get("debug_dir", "debug")

    # ---------- 感知 ----------
    def grab(self) -> np.ndarray:
        """最新窗口截图。"""
        started = time.perf_counter()
        img = cap.capture_window(self.win)
        _log.debug("截图 %dx%d %dms", img.shape[1], img.shape[0], _ms(started))
        return img

    def ocr(self, img: Optional[np.ndarray] = None, region=None) -> List[OcrResult]:
        """整图（或指定区域）OCR；region=[x1,y1,x2,y2] 时坐标仍回换算到整图。"""
        started = time.perf_counter()
        results = ocr_image(img if img is not None else self.grab(),
                            min_confidence=self.ocr_min_conf, region=region)
        _log.debug("OCR %d 条 %dms region=%s", len(results), _ms(started), region)
        return results

    def analyze(self, img: Optional[np.ndarray] = None,
                results: Optional[List[OcrResult]] = None) -> List[Region]:
        """区域融合（通用检测 → OCR 兜底），返回排好序的 Region 列表。

        复用传入的截图与全图 OCR 结果，保证一帧只做一次全图识别；
        不传则自行补齐，调用方无需关心内部顺序。
        """
        return analyze_regions(self, img, results)
