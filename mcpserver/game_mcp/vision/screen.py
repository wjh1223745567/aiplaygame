"""区域融合：通用检测（YOLO）找实体 + OCR 兜底文字，统一产出 Region 列表。

## 为什么还要「区域」而不是「一堆文字」

改造前是「全图 OCR → 逐条编号」。模型看到的只是零散文字与像素坐标，
分不清「这是按钮上的字」还是「正文里出现的同一句话」。

这里把 OCR 文字绑定到它所属的区域语义上，只有两层：

```
截图
 ├─ general 层：COCO 模型找游戏实体（人物/NPC）→ Region(kind=entity, source=general)
 └─ ocr 层   ：全图 OCR 中未被实体框认领的文本 → Region(kind=label, source=ocr)
```

## 融合顺序

1. 通用检测层（`general` 段，可选）跑一次找游戏实体：框之间 `IoU >= 0.7` 时只留置信度高的，
   其余作为 `kind=entity` 只读锚点；**不认领框内文字**，所以血条旁的怪物名照样进 label 层。
2. 全图 OCR 结果里，中心点落在实体框内的文本丢弃（已被认领）；
   剩下的按「同一行且水平相邻」聚合成 `kind=label` 区域。
3. 排序：entity 在前、label 在后，各自内部按 `(cy, cx)` 阅读序，总数截断 `max_n`。

界面控件（按钮/图标/面板选项）不走模型也不做模板匹配：这一版只输出「哪里有实体」与
「写了什么字」两条识别结果，是否要据此动作由上层决定。

## 降级

通用检测未就绪时全部文字落到 `kind=label`，调用方拿到的就是一帧按行聚合的文本。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import numpy as np

from ..core.log import get_logger
from .ocr import OcrResult
from .yolo import Detection

_log = get_logger("vision.screen")

Box = Tuple[int, int, int, int]

# 排序优先级（越小越靠前）
_KIND_ORDER = {"entity": 0, "label": 1}

_ENTITY_IOU_DROP = 0.7      # 两个实体框互相重叠到此程度时只留置信度高的
_ROW_JOIN_GAP = 1.6         # 同一行文字水平间距超过「字高 × 此倍数」就断开


@dataclass
class Region:
    """画面中的一个语义区域：类型 + 框 + 区内文字。"""

    kind: str                                   # entity | label
    box: Box
    text: str = ""
    source: str = "ocr"                         # general | ocr
    confidence: float = 0.0
    points: List[Tuple[int, int]] = field(default_factory=list)
    meta: dict = field(default_factory=dict)    # {"general_label": 原标签}

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2

    @property
    def center(self) -> Tuple[int, int]:
        return int(self.cx), int(self.cy)

    def to_dict(self) -> dict:
        """给 MCP 工具/日志用的扁平形式。"""
        return {"kind": self.kind, "source": self.source, "text": self.text,
                "box": list(self.box), "cx": int(self.cx), "cy": int(self.cy),
                **({"meta": self.meta} if self.meta else {})}


# ---------- 几何辅助 ----------
def _area(box: Box) -> int:
    return max(0, box[2] - box[0]) * max(0, box[3] - box[1])


def _iou(a: Box, b: Box) -> float:
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    if not inter:
        return 0.0
    union = _area(a) + _area(b) - inter
    return inter / union if union else 0.0


def _center_in(box: Box, cx: float, cy: float) -> bool:
    return box[0] <= cx <= box[2] and box[1] <= cy <= box[3]


def _union(boxes: List[Box]) -> Box:
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


def _overlaps_any(box: Box, boxes: List[Box], threshold: float) -> bool:
    return any(_iou(box, other) >= threshold for other in boxes)


def _texts_in(results: List[OcrResult]) -> Tuple[str, List[Tuple[int, int]]]:
    """把一批 OCR 结果按行序拼成文本，并给出每块文字的中心点。"""
    ordered = sorted(results, key=lambda r: (round(r.cy / 10), r.cx))
    seen = set()
    words: List[str] = []
    points: List[Tuple[int, int]] = []
    for r in ordered:
        t = (r.text or "").replace("\n", " ").strip()
        if not t or t in seen:
            continue
        seen.add(t)
        words.append(t)
        points.append((int(r.cx), int(r.cy)))
    return " ".join(words), points


# ---------- 各层 ----------
def detect_general(ctx, img: np.ndarray,
                   classes: Optional[List[str]] = None) -> List[Detection]:
    """通用检测层：COCO 模型找游戏实体（人物/NPC），只作只读锚点。

    classes 为空时用 general.classes 白名单（现为 person）。权重缺失/未装依赖时
    返回空列表，不影响 OCR 兜底层。
    """
    general = getattr(ctx, "general", None)
    if general is None:
        return []
    cfg = getattr(ctx, "cfg", {}) or {}
    gcfg = cfg.get("general", {}) or {}
    try:
        return general.detect(img, classes=list(classes or gcfg.get("classes") or []) or None)
    except Exception as e:  # noqa: BLE001
        _log.warning("通用检测失败，跳过该层: %s", e)
        return []


def _entity_regions(ctx, img: np.ndarray) -> List[Region]:
    """通用检测层：游戏实体只读锚点（不读框内文字，留给 label 层）。"""
    regions: List[Region] = []
    accepted: List[Box] = []
    for det in sorted(detect_general(ctx, img), key=lambda d: -d.confidence):
        box = tuple(int(v) for v in det.box)  # type: ignore[arg-type]
        if _overlaps_any(box, accepted, _ENTITY_IOU_DROP):
            continue
        accepted.append(box)  # type: ignore[arg-type]
        regions.append(Region(
            kind=det.kind or "entity",
            box=box,                                    # type: ignore[arg-type]
            text="",
            source="general",
            confidence=float(det.confidence),
            points=[],
            meta={"general_label": det.label},
        ))
    if regions:
        _log.debug("通用层命中 %d 个实体: %s", len(regions),
                   [r.meta.get("general_label") for r in regions])
    return regions


def _label_regions(results: List[OcrResult], claimed: List[Box]) -> List[Region]:
    """OCR 兜底层：未被任何实体框认领的文字按「同行且水平相邻」聚合成 label。"""
    leftovers = [r for r in results
                 if (r.text or "").strip()
                 and not any(_center_in(b, r.cx, r.cy) for b in claimed)]
    if not leftovers:
        return []
    ordered = sorted(leftovers, key=lambda r: (r.cy, r.cx))
    groups: List[List[OcrResult]] = []
    for r in ordered:
        if not groups:
            groups.append([r])
            continue
        last = groups[-1][-1]
        height = max(last.box[3] - last.box[1], 8)
        same_row = abs(r.cy - last.cy) <= height * 0.6
        adjacent = 0 <= r.box[0] - last.box[2] <= height * _ROW_JOIN_GAP
        if same_row and adjacent:
            groups[-1].append(r)
        else:
            groups.append([r])
    regions: List[Region] = []
    for group in groups:
        text, points = _texts_in(group)
        if not text:
            continue
        regions.append(Region(
            kind="label",
            box=_union([r.box for r in group]),
            text=text,
            source="ocr",
            confidence=max(r.confidence for r in group),
            points=points,
            meta={},
        ))
    if regions:
        _log.debug("OCR 兜底层聚合 %d 个 label: %s", len(regions),
                   [r.text[:16] for r in regions])
    return regions


# ---------- 对外入口 ----------
def analyze_regions(ctx, img: Optional[np.ndarray] = None,
                    results: Optional[List[OcrResult]] = None,
                    max_n: int = 50) -> List[Region]:
    """区域融合（通用检测 / OCR），返回排好序的 Region 列表（实体在前，文字在后）。

    ctx 为 BotContext（需要 cfg / general / ocr_min_conf）。
    results 传全图 OCR 结果可避免重复识别；为 None 时按需补一次。
    """
    if img is None:
        img = ctx.grab()
    if results is None:
        results = ctx.ocr(img)

    entities = _entity_regions(ctx, img)
    labels = _label_regions(results, [r.box for r in entities])

    regions = entities + labels
    regions.sort(key=lambda r: (_KIND_ORDER.get(r.kind, 9), round(r.cy), r.cx))
    merged = regions[:max(1, int(max_n))]
    _log.debug("区域融合：实体 %d / 文字 %d → 输出 %d",
               len(entities), len(labels), len(merged))
    return merged
