"""模板匹配：按钮/图标等固定 UI 元素的定位。

模板图存放于 assets/templates/，建议从游戏截图裁剪、保持原生分辨率。
支持多尺度匹配以兼容窗口缩放。
"""
from __future__ import annotations

import os
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np

# 模板图缓存：key 为绝对路径，值为 (mtime_ns, 彩色图, 灰度图)。
# 必须放模块级：之前用 @lru_cache 装饰实例方法，self 参与缓存 key，而每个工具调用都会
# 新建一个 TemplateMatcher —— 缓存一次都不会命中，且 lru_cache 永久持有旧实例与模板图。
_TPL_CACHE: Dict[str, Tuple[int, np.ndarray, np.ndarray]] = {}


def _load_template(path: str) -> Tuple[np.ndarray, np.ndarray]:
    """读模板图 (color, gray)，按路径 + mtime 缓存；文件改过后自动重读。"""
    try:
        stamp = os.stat(path).st_mtime_ns
    except OSError:
        stamp = 0
    hit = _TPL_CACHE.get(path)
    if hit is not None and hit[0] == stamp:
        return hit[1], hit[2]
    img = cv2.imread(path, cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f"模板不存在: {path}")
    entry = (stamp, img, cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
    _TPL_CACHE[path] = entry
    return entry[1], entry[2]


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
                 default_threshold: float = 0.85,
                 items: Optional[dict] = None, multi_scale: bool = False):
        self.templates_dir = templates_dir
        self.default_threshold = default_threshold
        # config.template.items：模板清单。新增模板只需裁图丢进 assets/templates/
        # 并加一行配置，不必改代码。
        self.items: dict = dict(items or {})
        self.multi_scale = bool(multi_scale)
        os.makedirs(templates_dir, exist_ok=True)

    def _path(self, name: str) -> str:
        path = name if os.path.isabs(name) else os.path.join(self.templates_dir, name)
        if not path.endswith((".png", ".jpg", ".jpeg", ".bmp")):
            path += ".png"
        return path

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
            tpl_color = template
            tpl_gray = cv2.cvtColor(template, cv2.COLOR_BGR2GRAY)
        else:
            try:
                tpl_color, tpl_gray = _load_template(self._path(template))
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
            if s == 1.0:
                tg = tpl_gray
            else:
                tg = cv2.cvtColor(cv2.resize(tpl_color, None, fx=s, fy=s), cv2.COLOR_BGR2GRAY)
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

    def scan(self, img: np.ndarray, items: Optional[dict] = None,
             multi_scale: Optional[bool] = None
             ) -> List[Tuple[str, dict, TemplateMatch]]:
        """按 config.template.items 逐项匹配，返回 (name, item_cfg, match)。

        文件缺失静默跳过（模板未标定属正常情况，不应报警也不应中断流程）。
        是否可点不在模板项里声明：统一由 config.vision.clickable_kinds 按
        item_cfg["kind"] 决定，避免两处开关互相矛盾。
        """
        cfg_items = self.items if items is None else items
        ms = self.multi_scale if multi_scale is None else bool(multi_scale)
        matches: List[Tuple[str, dict, TemplateMatch]] = []
        for name, item in (cfg_items or {}).items():
            if not isinstance(item, dict):
                continue
            tpl = item.get("file") or f"{name}.png"
            match = self.find(img, tpl, threshold=item.get("threshold"),
                              multi_scale=ms)
            if match is not None:
                matches.append((name, item, match))
        return matches

    def wait_for(self, img_provider, template: str, timeout: float = 10.0,
                 interval: float = 0.5, **kw) -> Optional[TemplateMatch]:
        """轮询直到模板出现或超时。img_provider 为无参函数，返回最新截图。"""
        end = time.time() + timeout
        while time.time() < end:
            m = self.find(img_provider(), template, **kw)
            if m:
                return m
            time.sleep(interval)
        return None
