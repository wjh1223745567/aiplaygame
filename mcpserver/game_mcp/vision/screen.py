"""多层视觉融合：模板 → UITag 控件 → 通用检测 → OCR，统一产出 Region 列表。

## 为什么需要「区域」而不是「一堆文字」

改造前是「全图 OCR → 逐条编号 → 模型选 id」。模型看到的只是零散文字与像素坐标，
分不清「确定按钮」和「正文里出现的『确定』二字」，这正是 `back_to_main` 乱点正文的根因。

这里把 OCR 文字绑定到它所属的区域语义上：

```
截图
 ├─ template 层：已知图标/固定元素 → Region(kind, source=template, 精确中心)
 ├─ yolo 层   ：UITag 分类控件 button/task/info/dialog/icon → 逐框 OCR(region=box) → Region(source=yolo)
 ├─ general 层：通用模型找游戏实体（人物/NPC）→ Region(kind=entity, source=general)
 ├─ ocr 层    ：全图 OCR 中「未被任何区域覆盖」的文本 → Region(kind=label, source=ocr) 兜底
 └─ 融合去重（template > yolo > general > ocr）
```

## 融合顺序（无歧义版）

1. `ctx.templates.scan()` 产出模板区域，`points[0]` 是命中中心（最精确的点击点）；
   模板框内的 OCR 文字会一并吸收进 `text`（模板给坐标、OCR 给语义，两者互不替代）。
2. YOLO 可用时逐框识别：与任一模板区域 `IoU >= 0.5` 的框**直接丢弃**（模板更精确）；
   其余框做区内 OCR，文字按行序拼成 `text`，各文本中心成为 `points`。
   框短边 < 12px 时不做区内 OCR（太小，识别出来也是噪声），只留 label 作为锚点。
3. 通用检测层（`general` 段，可选）再跑一次找游戏实体：与 UI 区域 `IoU >= 0.5`、
   或中心点落在可点区域里的实体框丢弃（UI 优先），其余作为 `kind=entity` 只读锚点，
   **不认领框内文字**，所以血条旁的怪物名依旧会进 label 层。
4. 全图 OCR 结果里，中心点落在任一已生成区域内的文本丢弃（已被认领）；
   剩下的按「同一行且水平相邻」聚合成 `kind=label` 区域。
5. 排序：可点类（button > icon > task）排前，只读类（dialog > entity > info > label）排后，
   各自内部按 `(cy, cx)` 阅读序，总数截断 `max_n`。

## 按需裁剪层

`analyze_regions(..., layers=("template",))` 只跑模板层：`back_to_main` 要的只是关闭键
模板坐标，没必要为此白跑两次模型推理（UITag + 通用）和每框一次区内 OCR。同理只要文字时
直接 `ctx.ocr()` 即可，不必付融合层的代价。

## 降级

模板目录为空、YOLO 未训练、两者都不可用时，全部文字落到 `kind=label`；
上层 `build_candidates` 只把「检测层认出的 button / icon 类」当可点候选，这些文字只能进只读上下文。
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import cv2
import numpy as np

from ..core.log import get_logger
from .ocr import OcrResult, ocr_image
from .yolo import Detection, resolve_kind

_log = get_logger("vision.screen")

Box = Tuple[int, int, int, int]

# 排序优先级（越小越靠前）。两张表的 kind 互不重叠：区域先按 _CLICKABLE_ORDER
# 分成「可点 / 只读」两组，各自再按自己那张表排序。
# 旧版只读表里也列了 icon/task/button，但分流之后它们不可能落在只读组，属于必须
# 手工同步的冗余项（新增可点类型时容易漏改），已删除。
_CLICKABLE_ORDER = {"button": 0, "icon": 1, "task": 2}
_READONLY_ORDER = {"dialog": 0, "entity": 1, "info": 2, "label": 3}

# analyze_regions 的三类来源（yolo 层内部再分 UITag / 通用两级）；
# 传 layers 可只跑需要的那几类
_ALL_LAYERS = ("template", "yolo", "ocr")

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


def screen_key(regions: List[Region]) -> frozenset:
    """画面指纹：全部区域文本的集合（模板区域用模板名补位）。

    识别画面不能只看按钮——按钮相同、聊天/任务追踪不同也是别的画面；
    无文字的区域若来自模板，用 ``@模板名`` 补位，否则这层信息就丢了。
    绕圈规避与失败案例去重共用此实现，避免两份实现漂移。
    """
    marks = set()
    for r in regions:
        if r.text:
            marks.add(r.text)
        elif (r.meta or {}).get("template"):
            marks.add("@" + str(r.meta["template"]))
    return frozenset(marks)


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


# ---------- 顶层图层（弹窗遮罩） ----------
# 亮度网格：一格约 33px（788 宽），够把面板边缘定到像素级
_TOP_GRID = (24, 18)
_TOP_BIMODAL = 2.5     # p75 >= p50 × 此倍数 才算「暗背景 + 亮面板」两簇
_TOP_ABS_BRIGHT = 120  # 亮簇的绝对下限：整屏都暗时不谈分层
_TOP_MIN_RATIO = 0.08  # 亮格占屏下限（太碎的是高光补丁，不是面板）
_TOP_MAX_RATIO = 0.80  # 上限：几乎全屏都亮说明没有遮罩
_TOP_MIN_FILL = 0.60   # 亮区外接框内的填充度：面板是矩形
_TOP_MARGIN = 24       # 外接框外扩：关闭键/按钮常压在面板边线上
_TOP_THICK = 0.3       # 投影厚度下限：排掉「标题栏」这种横跨一条的窄亮带


def _proj_runs(mask) -> List[Tuple[int, int]]:
    """一维 bool -> 连续段（允许 1 格间隙，面板中间被分隔线断一下不算断开）。"""
    idx = np.nonzero(mask)[0]
    if not len(idx):
        return []
    out: List[Tuple[int, int]] = []
    start = prev = idx[0]
    for i in idx[1:]:
        if i - prev > 2:
            out.append((start, prev))
            start = i
        prev = i
    out.append((start, prev))
    return out


def top_layer_box(img: np.ndarray) -> Optional[Box]:
    """找顶层面板矩形：弹窗打开时，被压暗的背景之外那块明亮的矩形。

    问道这类客户端弹对话框时会先盖一层半透黑遮罩再画面板，于是亮度直方图
    分成两簇（实测弹窗帧 p50=26 / p75=193，主场景只有 48 / 75）：取两簇几何
    中点当阈值，再按投影取连片矩形（投影能排掉窗口标题栏那种横贯一条的亮带）。

    返回 None 表示本帧没有分层，不要做任何门控。
    为什么要这个：遮罩下的元素根本点不动（点击被遮罩吃了），不能当可点候选——
    实测对话框打开时模板仍以 0.99 的分数命中暗处的 战斗教学/好友/实时，模型对着
    遮罩连点 77 步画面纹丝不动。
    """
    h, w = img.shape[:2]
    # 最大通道而非灰度：白/黄/红字的面板都亮，灰度会把彩色面板误判成暗区
    grid = cv2.resize(img, _TOP_GRID, interpolation=cv2.INTER_AREA).max(axis=2).astype(float)
    p50, p75 = np.percentile(grid, [50, 75])
    if p75 < _TOP_ABS_BRIGHT or p75 < p50 * _TOP_BIMODAL:
        return None
    bright = grid >= math.sqrt(p50 * p75)
    if not _TOP_MIN_RATIO <= bright.mean() <= _TOP_MAX_RATIO:
        return None
    cols = bright.sum(axis=0) >= max(3, _TOP_THICK * bright.sum(axis=0).max())
    if cols.sum() < 3:
        return None
    rows = bright[:, cols].sum(axis=1) >= max(3, _TOP_THICK * bright[:, cols].sum(axis=1).max())
    cr = _proj_runs(cols)
    rr = _proj_runs(rows)
    if not cr or not rr:
        return None
    x1, x2 = max(cr, key=lambda a: a[1] - a[0])
    y1, y2 = max(rr, key=lambda a: a[1] - a[0])
    y2 += 1
    x2 += 1
    if bright[y1:y2, x1:x2].mean() < _TOP_MIN_FILL:
        return None
    sx, sy = w / _TOP_GRID[0], h / _TOP_GRID[1]
    return (max(0, int(x1 * sx) - _TOP_MARGIN), max(0, int(y1 * sy) - _TOP_MARGIN),
            min(w, int(x2 * sx) + _TOP_MARGIN), min(h, int(y2 * sy) + _TOP_MARGIN))


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


def detect_layers(ctx, img: np.ndarray, layers: Tuple[str, ...] = ("ui", "general"),
                  classes: Optional[List[str]] = None) -> List[Detection]:
    """两级检测：先用 UITag 定控件，再用通用模型找游戏实体，合并后去重。

    layers 决定跑哪几层（`ui` / `general`）：只跑 ui 或只跑 general 时原样返回该层结果、
    不做跨层去重（调用方明确只要这一层）。classes 非空时同时作用于两层。
    去重规则（UI 优先）：与 UITag 框 IoU >= 0.5 的实体框丢弃；中心点落在可点 UI 区域
    （头像按钮之类）里的也丢弃；实体框之间按 IoU 0.7 去重。返回 UITag 结果在前。
    任一层不可用（权重缺失/未安装）只会少一半结果，不影响另一层。
    """
    ui_dets: List[Detection] = []
    if "ui" in layers:
        try:
            ui_dets = ctx.yolo.detect_ui(img, classes=classes)
        except Exception as e:  # noqa: BLE001
            _log.warning("UITag 检测失败，跳过该层: %s", e)
    general = getattr(ctx, "general", None)
    if "general" not in layers or general is None:
        return ui_dets
    cfg = getattr(ctx, "cfg", {}) or {}
    gcfg = cfg.get("general", {}) or {}
    clickable = set((cfg.get("vision", {}) or {}).get("clickable_kinds") or ("button", "icon"))
    ui_boxes = [d.box for d in ui_dets]
    # 只有小控件级别的命中才参与实体去重：icon 类经常出半屏大的容器框（一整块
    # Visual_Elements/icon 罩住整个对话框），拿它当热区会把面板里的 NPC 实体锚点
    # 整块吃掉——这种大框在 _yolo_regions 里本来就被降级成 info，也不可能是可点项。
    img_area = max(1, img.shape[0] * img.shape[1])
    hot_boxes = [d.box for d in ui_dets
                 if d.kind in clickable and _area(d.box) <= img_area * _CONTAINER_AREA_RATIO]
    try:
        g_dets = general.detect(img, classes=list(classes or gcfg.get("classes") or []) or None)
    except Exception as e:  # noqa: BLE001
        _log.warning("通用检测失败，跳过该层: %s", e)
        return ui_dets
    kept: List[Detection] = []
    accepted: List[Box] = []
    for d in sorted(g_dets, key=lambda x: -x.confidence):
        if _overlaps_any(d.box, ui_boxes, _TEMPLATE_IOU_DROP):
            continue                                # UITag 已说明这块是什么，不重复报
        if any(_center_in(b, d.cx, d.cy) for b in hot_boxes):
            continue                                # 落在按钮里的人物/图标是 UI 内容
        if _overlaps_any(d.box, accepted, _YOLO_IOU_DROP):
            continue
        accepted.append(d.box)
        kept.append(d)
    if kept:
        _log.debug("通用层补充 %d 个实体: %s", len(kept), sorted({d.label for d in kept}))
    return ui_dets + kept


def _yolo_regions(ctx, img: np.ndarray, ocr_min_conf: float,
                  template_boxes: List[Box],
                  layers: Tuple[str, ...] = ("ui", "general")) -> List[Region]:
    """YOLO 层：UITag 控片区 + 通用实体区（实体不读框内文字，留给 label 层）。"""
    img_area = max(1, img.shape[0] * img.shape[1])
    dets = detect_layers(ctx, img, layers=layers)
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
        is_entity = det.layer == "general" or kind == "entity"
        # 超大框当容器：不参与可点，也不做区内 OCR（否则等于把整屏 OCR 再跑一遍，
        # 而且这些文字本来就由 label 兜底层给出）。实体框不受此限。
        is_container = not is_entity and _area(box) > img_area * _CONTAINER_AREA_RATIO
        if is_container:
            kind = "info"
        text, points = "", []
        if short_side >= _MIN_BOX_SIDE and not is_container and not is_entity:
            try:
                text, points = _texts_in(ocr_image(img, min_confidence=ocr_min_conf,
                                                   region=box))  # type: ignore[arg-type]
            except Exception as e:  # noqa: BLE001
                _log.debug("YOLO 框内 OCR 失败（%s），仅保留区域", e)
        regions.append(Region(
            kind="entity" if is_entity else kind,
            box=box,                                    # type: ignore[arg-type]
            text=text,
            source="general" if is_entity else "yolo",
            confidence=float(det.confidence),
            points=points,
            # container 标记供上层决定「要不要认领文字」，不用再算一次面积、也
            # 不依赖「kind 被降级成 info」这个副作用
            meta={"general_label": det.label} if is_entity
            else {"yolo_label": det.label, "container": True} if is_container
            else {"yolo_label": det.label},
        ))
    if regions:
        _log.debug("YOLO 层命中 %d 处: %s", len(regions),
                   [r.meta.get("yolo_label") or r.meta.get("general_label") for r in regions])
    return regions


# ---------- 面板内的可点选项（彩色文本行） ----------
# 问道对话框的选项没有底框，靠字色区分：可点的画彩色、纯说明的画白色。
# 实测同一帧字身像元的饱和占比：选项 0.58-0.64 / 正文 0.12-0.13 / NPC 名 0.22，
# 差 4~5 倍——比形状、边缘、行高那些判据稳得多（行高根本分不开）。
_OPTION_SAT = 0.45       # 饱和占比下限：过了就当「彩色可点行」
_OPTION_BRIGHT = 120     # 算字身的亮度下限（低于它是面板底色，不参与统计）
_OPTION_CHROMA = 40      # 单像元算「有颜色」的 max-min 通道差下限
_OPTION_MIN_SIDE = 10    # 太短的行是角标/时间戳，不当选项


def _glyph_sat(img: np.ndarray, box: Box) -> float:
    """框内字身像元的饱和占比：亮像元里 max-min 通道差超阈值的比例。"""
    crop = img[box[1]:box[3], box[0]:box[2], :3].astype(np.int16)
    if crop.size == 0:
        return 0.0
    bright = crop.max(axis=2) >= _OPTION_BRIGHT
    if not bright.any():
        return 0.0
    px = crop[bright]
    return float(((px.max(axis=1) - px.min(axis=1)) > _OPTION_CHROMA).mean())


def option_regions(img: np.ndarray, top_box: Box, results: List[OcrResult],
                   claimed: List[Box]) -> List[Region]:
    """顶层面板内的「彩色文本行」当可点选项（kind=button、source=option）。

    只在 top_box 命中（画面真的分层了）时调用，所以聊天频道、任务追踪里那些
    彩色字不会被误当选项——它们不在面板内。已被模板/检测层认领的行不进（否则
    「离开」会同时出现一个 YOLO 按钮和一个 option 选项）。
    为什么不靠 YOLO 认这些行：UITag 在整个对话区只给一个 Visual_Elements/icon 大框（占屏
    36%，刚过容器阈值被降级成 info），选项本身根本没有可点信号。
    """
    out: List[Region] = []
    for r in results:
        text = (r.text or "").strip()
        if len(text) < 2 or not _center_in(top_box, r.cx, r.cy):
            continue
        if r.box[3] - r.box[1] < _OPTION_MIN_SIDE:
            continue
        if any(_center_in(b, r.cx, r.cy) for b in claimed):
            continue
        sat = _glyph_sat(img, r.box)
        if sat < _OPTION_SAT:
            continue
        out.append(Region(
            kind="button", box=r.box, text=text, source="option",
            confidence=r.confidence, points=[(int(r.cx), int(r.cy))],
            meta={"sat": round(sat, 2)},
        ))
    if out:
        _log.info("面板内彩色选项 %d 行: %s",
                  len(out), [(o.text[:10], o.meta["sat"]) for o in out])
    return out


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
    if regions:
        _log.debug("OCR 兜底层聚合 %d 个 label: %s", len(regions),
                   [r.text[:16] for r in regions])
    return regions


# ---------- 对外入口 ----------
def analyze_regions(ctx, img: Optional[np.ndarray] = None,
                    results: Optional[List[OcrResult]] = None,
                    max_n: int = 50,
                    layers: Optional[Tuple[str, ...]] = None) -> List[Region]:
    """多层融合（模板 / UITag / 通用 / OCR），返回排好序的 Region 列表（可点类在前，只读类在后）。

    ctx 为 BotContext（需要 cfg / templates / yolo / general / ocr_min_conf）。
    results 传全图 OCR 结果可避免重复识别；为 None 时按需补一次。
    layers 只影响「跑哪几层」（template / yolo / ocr），省略即全跑：只关心模板坐标时
    传 `("template",)` 可以省掉两次模型推理；只关心文字时直接用 `ctx.ocr()` 更便宜。
    """
    wanted = tuple(layers or _ALL_LAYERS)
    if img is None:
        img = ctx.grab()
    if results is None:
        results = ctx.ocr(img)
    ocr_min_conf = getattr(ctx, "ocr_min_conf", 0.5)

    template_regions = _template_regions(ctx, img, results) if "template" in wanted else []
    yolo_regions = (_yolo_regions(ctx, img, ocr_min_conf, [r.box for r in template_regions])
                    if "yolo" in wanted else [])
    claimed = [r.box for r in template_regions]
    # 大容器框（和实体框）不认领文字：否则一块「窗口/面板/工具栏」就把整屏 OCR
    # 全吃进 info 区，只读上下文里就再也看不到那些文字了。
    for r in yolo_regions:
        if r.kind == "entity" or (r.meta or {}).get("container"):
            continue
        claimed.append(r.box)
    # 弹窗打开时，面板内的彩色文本行就是可点选项（见 option_regions）
    option_layer: List[Region] = []
    vision = (getattr(ctx, "cfg", {}) or {}).get("vision", {}) or {}
    if "ocr" in wanted and vision.get("option_by_color", True):
        top_box = top_layer_box(img)
        if top_box:
            option_layer = option_regions(img, top_box, results, claimed)
            claimed += [r.box for r in option_layer]
    label_regions = _label_regions(results, claimed) if "ocr" in wanted else []

    regions = template_regions + yolo_regions + option_layer + label_regions
    clickable = sorted(
        [r for r in regions if r.kind in _CLICKABLE_ORDER],
        key=lambda r: (_CLICKABLE_ORDER.get(r.kind, 9), round(r.cy), r.cx))
    readonly = sorted(
        [r for r in regions if r.kind not in _CLICKABLE_ORDER],
        key=lambda r: (_READONLY_ORDER.get(r.kind, 9), round(r.cy), r.cx))
    merged = (clickable + readonly)[:max(1, int(max_n))]
    _log.debug("区域融合：模板 %d / 检测 %d / 选项 %d / 兜底 %d → 可点 %d + 只读 %d",
               len(template_regions), len(yolo_regions), len(option_layer),
               len(label_regions), len(clickable), len(readonly))
    return merged
