"""基础能力统一上下文：截图、识别、模板/YOLO、点击按键一站式入口。
可逐项检验的基础能力封装，供 cli 的各检验命令复用。
"""
from __future__ import annotations

import time
from typing import List, Optional

import numpy as np

from ..core import capture as cap
from ..core.input import InputController, is_stopped
from ..core.window import WindowInfo
from ..vision.ocr import OcrResult, find_text, ocr_image, match_text
from ..vision.llm import Advisor
from ..vision.template import TemplateMatch, TemplateMatcher
from ..vision.yolo import YoloDetector
from .log import get_logger

_log = get_logger("core.engine")


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


class BotContext:
    """基础能力上下文：截图、识别、点击一站式完成。"""

    def __init__(self, cfg: dict, win: WindowInfo):
        self.cfg = cfg
        self.win = win
        self.input = InputController(win, cfg.get("input", {}))
        self.templates = TemplateMatcher(
            cfg.get("template", {}).get("templates_dir", "assets/templates"),
            cfg.get("template", {}).get("default_threshold", 0.85),
        )
        self.yolo = YoloDetector(cfg.get("yolo", {}))
        self.ocr_min_conf = cfg.get("ocr", {}).get("min_confidence", 0.5)
        self.debug_dir = cfg.get("capture", {}).get("debug_dir", "debug")
        self.save_debug = cfg.get("capture", {}).get("save_debug", True)
        # 大模型决策助手（未配置 key 时相关调用会安全降级为 None/异常）
        self.advisor = Advisor(cfg)

    # ---------- 感知 ----------
    def grab(self) -> np.ndarray:
        """最新窗口截图。"""
        started = time.perf_counter()
        img = cap.capture_window(self.win)
        _log.debug("截图 %dx%d %dms", img.shape[1], img.shape[0], _ms(started))
        return img

    def ocr(self, img: Optional[np.ndarray] = None, region=None) -> List[OcrResult]:
        started = time.perf_counter()
        results = ocr_image(img if img is not None else self.grab(),
                            min_confidence=self.ocr_min_conf, region=region)
        _log.debug("OCR %d 条 %dms region=%s", len(results), _ms(started), region)
        return results

    def find_text(self, keyword: str, img: Optional[np.ndarray] = None,
                  region=None, exact: bool = False) -> Optional[OcrResult]:
        return find_text(img if img is not None else self.grab(), keyword,
                         exact=exact, min_confidence=self.ocr_min_conf, region=region)

    def snapshot(self):
        """一次截图 + 一次 OCR，返回 (img, results)。

        对同一画面做多关键字判定时应复用它，避免重复整图 OCR 拖慢速度。
        """
        img = self.grab()
        return img, self.ocr(img)

    @staticmethod
    def find_in(results, keyword: str, exact: bool = False) -> Optional[OcrResult]:
        """在 snapshot 的 results 里匹配关键字（不再 OCR）。"""
        return match_text(results, keyword, exact=exact)

    def find_template(self, name: str, img: Optional[np.ndarray] = None,
                      region=None, threshold: Optional[float] = None,
                      multi_scale: bool = False) -> Optional[TemplateMatch]:
        return self.templates.find(img if img is not None else self.grab(), name,
                                   threshold=threshold, region=region,
                                   multi_scale=multi_scale)

    # ---------- 等待 ----------
    def wait_text(self, keyword: str, timeout: float = 15.0,
                  interval: float = 0.8) -> Optional[OcrResult]:
        """轮询截图直到关键字出现。"""
        end = time.time() + timeout
        while time.time() < end and not is_stopped():
            hit = self.find_text(keyword)
            if hit:
                return hit
            time.sleep(interval)
        return None

    def wait_template(self, name: str, timeout: float = 15.0,
                      interval: float = 0.8, **kw) -> Optional[TemplateMatch]:
        return self.templates.wait_for(self.grab, name, timeout=timeout,
                                       interval=interval, **kw)

    def wait_gone(self, keyword: str, timeout: float = 20.0,
                  interval: float = 1.0) -> bool:
        """等待某文字从画面消失（如加载提示、进度文案）。"""
        end = time.time() + timeout
        while time.time() < end and not is_stopped():
            if self.find_text(keyword) is None:
                return True
            time.sleep(interval)
        return False

    # ---------- 行动 ----------
    def click_point(self, px: float, py: float, **kw) -> None:
        self.input.click(px, py, **kw)

    def click_text(self, keyword: str, img: Optional[np.ndarray] = None,
                   region=None, timeout: float = 10.0) -> bool:
        """找到包含关键字的文本并点击其中心。"""
        if img is not None:
            hit = self.find_text(keyword, img=img, region=region)
        else:
            hit = self.wait_text(keyword, timeout=timeout) if timeout > 0 \
                else self.find_text(keyword, region=region)
        if hit is None:
            _log.debug("未找到文字 %r，放弃点击", keyword)
            return False
        _log.info("点击文字 %r (%d,%d)", keyword, hit.cx, hit.cy)
        self.input.click(hit.cx, hit.cy)
        return True

    def click_template(self, name: str, timeout: float = 10.0, **kw) -> bool:
        m = (self.templates.wait_for(self.grab, name, timeout=timeout, **kw)
             if timeout > 0 else self.find_template(name, **kw))
        if m is None:
            _log.debug("未找到模板 %r，放弃点击", name)
            return False
        _log.info("点击模板 %r (%d,%d)", name, m.cx, m.cy)
        self.input.click(m.cx, m.cy)
        return True

    def snap(self, tag: str) -> Optional[str]:
        """保存调试截图。"""
        if not self.save_debug:
            return None
        try:
            path = cap.debug_save(self.grab(), tag, self.debug_dir)
            _log.info("保存调试截图 %s", path)
            return path
        except Exception as e:
            _log.warning("保存调试截图失败 tag=%s: %s", tag, e)
            return None
