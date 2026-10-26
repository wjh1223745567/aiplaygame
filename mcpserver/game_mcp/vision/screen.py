"""三层视觉融合：模板 → YOLO → OCR，统一产出 Region 列表。

## 为什么需要「区域」而不是「一堆文字」

改造前是「全图 OCR → 逐条编号 → 模型选 id」。模型看到的只是零散文字与像素坐标，
分不清「确定按钮」和「正文里出现的『确定』二字」，这正是 `back_to_main` 乱点正文的根因。

这里把 OCR 文字绑定到它所属的区域语义上：

```
截图
 ├─ template 层：已知图标/固定元素 → Region(kind, source=template, 精确中心)
 ├─ yolo 层   ：UI 区域分类 button/task/info/dialog/icon → 逐框 OCR(region=box) → Region(kind, source=yolo)
 ├─ ocr 层    ：全图 OCR 中「未被任何区域覆盖」的文本 → Region(kind=label, source=ocr) 兜底
 └─ 融合去重（template > yolo > ocr）
```

## 融合顺序（无歧义版）

1. `ctx.templates.scan()` 产出模板区域，`points[0]` 是命中中心（最精确的点击点）；
   模板框内的 OCR 文字会一并吸收进 `text`（模板给坐标、OCR 给语义，两者互不替代）。
2. YOLO 可用时逐框识别：与任一模板区域 `IoU >= 0.5` 的框**直接丢弃**（模板更精确）；
   其余框做区内 OCR，文字按行序拼成 `text`，各文本中心成为 `points`。
   框短边 < 12px 时不做区内 OCR（太小，识别出来也是噪声），只留 label 作为锚点。
3. 全图 OCR 结果里，中心点落在任一已生成区域内的文本丢弃（已被认领）；
   剩下的按「同一行且水平相邻」聚合成 `kind=label` 区域。
4. 排序：可点类（button > icon > task）排前，只读类（dialog > task > info > label）排后，
   各自内部按 `(cy, cx)` 阅读序，总数截断 `max_n`。

## 降级

模板目录为空、YOLO 未训练、两者都不可用时，全部文字落到 `kind=label`，
上层 `build_candidates` 走 `empty_clickable_fallback` 退回改动前的纯文本候选行为。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import numpy as np

from ..core.log import get_logger
from .ocr import OcrResult, ocr_image
from .yolo import resolve_kind

_log = get_logger("vision.screen")

Box = Tuple[int, int, int, int]

# 同一个 kind 集合内的排序优先级（越小越靠前）
_CLICKABLE_ORDER = {"button": 0, "icon": 1, "task": 2}
_READONLY_ORDER = {"dialog": 0, "task": 1, "info": 2, "icon": 3, "label": 4, "button": 5}

_MIN_BOX_SIDE = 12          # 小于此边长的框不做区内 OCR
_TEMPLATE_IOU_DROP = 0.5    # 与模板命中重叠到此程度的 YOLO 框丢弃
_YOLO_IOU_DROP = 0.7        # 两个 YOLO 框互相重叠到此程度时只留置信度高的
_ROW_JOIN_GAP = 1.6         # 同一行文字水平间距超过「字高 × 此倍数」就断开
# 面积超过整图此比例的 YOLO 框当容器处理：不参与可点、也不认领框内文字
# （未训练/换模型时容易出「整屏一个 Button」这种框，认领文字会把兜底候选吃干）
_CONTAINER_AREA_RATIO = 0.35


@dataclass
class Region:
    """画面中的一个语义区域：类型 + 框 + 区内文字 + 可点击文字中心。"""

    kind: str                                   # button | task | info | dialog | icon | label
    box: Box
    text: str = ""
    source: str = "ocr"                         # template | yolo | ocr
    confidence: float = 0.0
    points: List[Tuple[int, int]] = field(default_factory=list)
    meta: dict = field(default_factory=dict)    # {"template": name} 或 {"yolo_label": 原标签}

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
def _template_regions(ctx, img: np.ndarray,
                    results: Optional[List[OcrResult]] = None) -> List[Region]:
    """模板层：命中即区域，中心点最精确；框内 OCR 文字一并吸收。"""
    items = (ctx.cfg.get("template", {}) or {}).get("items", {}) or {}
    if not items:
        return []
    regions: List[Region] = []
    try:
        matches = ctx.templates.scan(img, items)
    except Exception as e:  # noqa: BLE001 模板层异常不能拖垮整条流水线
        _log.warning("模板扫描失败，跳过该层: %s", e)
        return []
    for name, item_cfg, match in matches:
        box = tuple(int(v) for v in match.box)  # type: ignore[arg-type]
        if _overlaps_any(box, [r.box for r in regions], _TEMPLATE_IOU_DROP):
            continue                                    # 多个模板命中同一处，留置信度高的
        # 模板图本身是图标/按钮截图，框内往往还有文字：吸收进来供模型与文案判定使用。
        # 点击点仍以模板命中中心为准（比 OCR 中心稳）。
        inner = [r for r in (results or []) if _center_in(box, r.cx, r.cy)]
        text, text_points = _texts_in(inner)
        cx, cy = int(match.cx), int(match.cy)
        regions.append(Region(
            kind=str(item_cfg.get("kind", "icon")),
            box=box,                                   # type: ignore[arg-type]
            text=text,
            source="template",
            confidence=float(match.score),
            points=[(cx, cy)] + [p for p in text_points if p != (cx, cy)],
            meta={"template": name},
        ))
    if regions:
        _log.debug("模板层命中 %d 处: %s", len(regions),
                   [r.meta.get("template") for r in regions])
    return regions


def _yolo_regions(ctx, img: np.ndarray, ocr_min_conf: float,
                  template_boxes: List[Box]) -> List[Region]:
    """YOLO 层：只做区域分类，区内文字由 OCR 给出。"""
    img_area = max(1, img.shape[0] * img.shape[1])
    try:
        dets = ctx.yolo.detect_ui(img)
    except Exception as e:  # noqa: BLE001
        _log.warning("YOLO 检测失败，跳过该层: %s", e)
        return []
    regions: List[Region] = []
    accepted: List[Box] = []
    for det in sorted(dets, key=lambda d: -d.confidence):
        box = tuple(int(v) for v in det.box)  # type: ignore[arg-type]
        if _overlaps_any(box, template_boxes, _TEMPLATE_IOU_DROP):
            continue                                    # 模板更精确，丢弃 YOLO 框
        if _overlaps_any(box, accepted, _YOLO_IOU_DROP):
            continue
        accepted.append(box)  # type: ignore[arg-type]
        short_side = min(box[2] - box[0], box[3] - box[1])
        kind = det.kind or resolve_kind(det.label)
        # 超大框当容器：不参与可点，也不做区内 OCR（否则等于把整屏 OCR 再跑一遍，
        # 而且这些文字本来就由 label 兜底层给出）
        is_container = _area(box) > img_area * _CONTAINER_AREA_RATIO
        if is_container:
            kind = "info"
        text, points = "", []
        if short_side >= _MIN_BOX_SIDE and not is_container:
            try:
                text, points = _texts_in(ocr_image(img, min_confidence=ocr_min_conf,
                                                   region=box))  # type: ignore[arg-type]
            except Exception as e:  # noqa: BLE001
                _log.debug("YOLO 框内 OCR 失败（%s），仅保留区域", e)
        regions.append(Region(
            kind=kind,
            box=box,                                    # type: ignore[arg-type]
            text=text,
            source="yolo",
            confidence=float(det.confidence),
            points=points,
            meta={"yolo_label": det.label},
        ))
    if regions:
        _log.debug("YOLO 层命中 %d 处: %s", len(regions),
                   [r.meta.get("yolo_label") for r in regions])
    return regions


def _label_regions(results: List[OcrResult], claimed: List[Box]) -> List[Region]:
    """OCR 兜底层：未被任何区域认领的文字按「同行且水平相邻」聚合成 label。"""
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
    return regions


# ---------- 对外入口 ----------
def analyze_regions(ctx, img: Optional[np.ndarray] = None,
                    results: Optional[List[OcrResult]] = None,
                    max_n: int = 50) -> List[Region]:
    """三层融合，返回排好序的 Region 列表（可点类在前，只读类在后）。

    ctx 为 BotContext（需要 cfg / templates / yolo / ocr_min_conf）。
    results 传全图 OCR 结果可避免重复识别；为 None 时按需补一次。
    """
    if img is None:
        img = ctx.grab()
    if results is None:
        results = ctx.ocr(img)
    ocr_min_conf = getattr(ctx, "ocr_min_conf", 0.5)

    template_regions = _template_regions(ctx, img, results)
    yolo_regions = _yolo_regions(ctx, img, ocr_min_conf,
                                [r.box for r in template_regions])
    claimed = [r.box for r in template_regions]
    # 大容器框不认领文字：否则一块「窗口/面板/工具栏」就把整屏 OCR 全吃进 info 区，
    # label 层拿不到文字，empty_clickable_fallback 也就无从降级。
    for r in yolo_regions:
        if r.kind == "info" and _area(r.box) > img.shape[0] * img.shape[1] * _CONTAINER_AREA_RATIO:
            continue
        claimed.append(r.box)
    label_regions = _label_regions(results, claimed)

    regions = template_regions + yolo_regions + label_regions
    clickable = sorted(
        [r for r in regions if r.kind in _CLICKABLE_ORDER],
        key=lambda r: (_CLICKABLE_ORDER.get(r.kind, 9), round(r.cy), r.cx))
    readonly = sorted(
        [r for r in regions if r.kind not in _CLICKABLE_ORDER],
        key=lambda r: (_READONLY_ORDER.get(r.kind, 9), round(r.cy), r.cx))
    merged = (clickable + readonly)[:max(1, int(max_n))]
    _log.debug("区域融合：模板 %d / YOLO %d / 兜底 %d → 可点 %d + 只读 %d",
               len(template_regions), len(yolo_regions), len(label_regions),
               len(clickable), len(readonly))
    return merged
