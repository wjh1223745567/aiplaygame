"""基础能力统一上下文：截图、识别、模板/YOLO、点击按键一站式入口。
可逐项检验的基础能力封装，供 cli 的各检验命令复用。
"""
from __future__ import annotations

import time
from typing import List, Optional

import numpy as np

from ..core import capture as cap
from ..core.human_guard import get_guard
from ..core.input import InputController, is_stopped
from ..core.window import WindowInfo
from ..vision.ocr import OcrResult, find_text, ocr_image, match_text
from ..vision.llm import Advisor
from ..vision.screen import Region, analyze_regions
from ..vision.template import TemplateMatcher
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
        tpl_cfg = cfg.get("template", {}) or {}
        self.templates = TemplateMatcher(
            tpl_cfg.get("templates_dir", "assets/templates"),
            tpl_cfg.get("default_threshold", 0.85),
            items=tpl_cfg.get("items", {}),
            multi_scale=bool(tpl_cfg.get("multi_scale", False)),
        )
        self.yolo = YoloDetector(cfg.get("yolo", {}))
        # 第二级：通用检测（COCO 等）用来找游戏实体，与 UITag 结果去重后只作只读锚点
        self.general = YoloDetector({**(cfg.get("general", {}) or {}), "layer": "general"})
        self.ocr_min_conf = cfg.get("ocr", {}).get("min_confidence", 0.5)
        self.debug_dir = cfg.get("capture", {}).get("debug_dir", "debug")
        # 大模型决策助手（未配置 key 时相关调用会安全降级为 None/异常）
        self.advisor = Advisor(cfg)
        # 人工接管保护：鼠标被人移动/点击即暂停全部输入（start 幂等，无权限时自动降级）
        self.human_guard = get_guard(cfg.get("human_guard", {}))
        self.human_guard.start()

    # ---------- 人工接管保护 ----------
    def guard(self, max_block_sec: float = 0.0) -> bool:
        """暂停中则等待恢复。返回 False 表示应中止当前流程（急停或等待超时）。"""
        return self.human_guard.wait_if_paused(max_block_sec)

    def guard_generation(self) -> int:
        """暂停代次：与计算前不一致说明中途被人工接管，算出的坐标已作废。"""
        return self.human_guard.generation

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

    def analyze(self, img: Optional[np.ndarray] = None,
                results: Optional[List[OcrResult]] = None,
                layers: Optional[tuple] = None) -> List[Region]:
        """多层视觉融合（模板 → UITag → 通用 → OCR），返回排好序的 Region 列表。

        复用传入的截图与全图 OCR 结果，保证一帧只做一次全图识别；
        不传则自行补齐，调用方无需关心内部顺序。
        layers 可只跑需要的层（如 `("template",)`）：只有模板坐标的需求不必付
        YOLO/实体推理的代价，见 vision/screen.py 的「按需裁剪层」。
        """
        return analyze_regions(self, img, results, layers=layers)

    @staticmethod
    def find_in(results, keyword: str, exact: bool = False) -> Optional[OcrResult]:
        """在 snapshot 的 results 里匹配关键字（不再 OCR）。"""
        return match_text(results, keyword, exact=exact)

    # ---------- 等待 ----------
    def wait_text(self, keyword: str, timeout: float = 15.0,
                  interval: float = 0.8) -> Optional[OcrResult]:
        """轮询截图直到关键字出现。"""
        end = time.time() + timeout
        while time.time() < end and not is_stopped():
            if not self.guard():
                return None
            hit = self.find_text(keyword)
            if hit:
                return hit
            time.sleep(interval)
        return None

    # ---------- 行动 ----------
    def click_point(self, px: float, py: float, **kw) -> bool:
        """点像素坐标；返回是否真的发出（窗口不在前台时为 False，不会误点到其他应用）。"""
        return self.input.click(px, py, **kw)

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
        return self.input.click(hit.cx, hit.cy)
