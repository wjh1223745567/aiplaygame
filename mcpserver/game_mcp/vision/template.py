"""模板匹配：按钮/图标等固定 UI 元素的定位。

模板图存放于 assets/templates/，建议从游戏截图裁剪、保持原生分辨率。
支持多尺度匹配以兼容窗口缩放。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from typing import List, Optional, Tuple

import cv2
import numpy as np


@dataclass
class TemplateMatch:
    name: str
    score: float
    box: Tuple[int, int, int, int]  # x1, y1, x2, y2

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2


class TemplateMatcher:
    def __init__(self, templates_dir: str = "assets/templates",
                 default_threshold: float = 0.85):
        self.templates_dir = templates_dir
        self.default_threshold = default_threshold
        os.makedirs(templates_dir, exist_ok=True)

    @lru_cache(maxsize=64)
    def _load(self, name: str) -> np.ndarray:
        path = name if os.path.isabs(name) else os.path.join(self.templates_dir, name)
        if not path.endswith((".png", ".jpg", ".jpeg", ".bmp")):
            path += ".png"
        if not os.path.exists(path):
            raise FileNotFoundError(f"模板不存在: {path}")
        img = cv2.imread(path, cv2.IMREAD_COLOR)
        if img is None:
            raise FileNotFoundError(f"模板不存在: {path}")
        return img

    def find(self, img: np.ndarray, template: str,
             threshold: Optional[float] = None,
             region: Optional[Tuple[int, int, int, int]] = None,
             multi_scale: bool = False) -> Optional[TemplateMatch]:
        """匹配单个模板，返回最高分（低于阈值返回 None）。

        template 可以是模板文件名，也可以直接传 BGR 图像。
        """
        thr = threshold if threshold is not None else self.default_threshold
        result = self.find_all(img, template, threshold=thr, region=region,
                               multi_scale=multi_scale, top_n=1)
        return result[0] if result else None

    def find_all(self, img: np.ndarray, template: str,
                 threshold: Optional[float] = None,
                 region: Optional[Tuple[int, int, int, int]] = None,
                 multi_scale: bool = False, top_n: int = 5) -> List[TemplateMatch]:
        thr = threshold if threshold is not None else self.default_threshold
        offset_x = offset_y = 0
        search = img
        if region:
            x1, y1, x2, y2 = region
            offset_x, offset_y = x1, y1
            search = img[y1:y2, x1:x2]

        if isinstance(template, np.ndarray):
            tpl = template
        else:
            try:
                tpl = self._load(template)
            except FileNotFoundError:
                # 备用模板尚未标定属正常情况：视为未匹配，不中断 OCR 主流程
                return []
        name = os.path.basename(str(template)) if not isinstance(template, np.ndarray) else "inline"

        scales = [1.0]
        if multi_scale:
            scales = [0.85, 0.9, 0.95, 1.0, 1.05, 1.1, 1.15]

        best: List[TemplateMatch] = []
        gray = cv2.cvtColor(search, cv2.COLOR_BGR2GRAY)
        for s in scales:
            t = cv2.resize(tpl, None, fx=s, fy=s) if s != 1.0 else tpl
            tg = cv2.cvtColor(t, cv2.COLOR_BGR2GRAY)
            th, tw = tg.shape[:2]
            if th > gray.shape[0] or tw > gray.shape[1] or th < 4 or tw < 4:
                continue
            res = cv2.matchTemplate(gray, tg, cv2.TM_CCOEFF_NORMED)
            # 非极大值抑制取前 top_n 个位置
            work = res.copy()
            for _ in range(top_n):
                _, max_val, _, max_loc = cv2.minMaxLoc(work)
                if max_val < thr:
                    break
                x1 = max_loc[0] + offset_x
                y1 = max_loc[1] + offset_y
                best.append(TemplateMatch(name, float(max_val),
                                         (x1, y1, x1 + tw, y1 + th)))
                # 屏蔽已匹配区域
                sy, sx = max(th // 2, 4), max(tw // 2, 4)
                work[max(0, max_loc[1] - sy):max_loc[1] + sy,
                     max(0, max_loc[0] - sx):max_loc[0] + sx] = -1.0
            if best:
                break  # 某尺度命中后不再尝试其他尺度
        return best

    def wait_for(self, img_provider, template: str, timeout: float = 10.0,
                 interval: float = 0.5, **kw) -> Optional[TemplateMatch]:
        """轮询直到模板出现或超时。img_provider 为无参函数，返回最新截图。"""
        import time
        end = time.time() + timeout
        while time.time() < end:
            m = self.find(img_provider(), template, **kw)
            if m:
                return m
            time.sleep(interval)
        return None
