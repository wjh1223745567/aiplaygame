"""YOLO 目标检测（可选模块，只做 UI 区域分类）。

推荐权重 `yolo-ui.pt`（YOLO11s 在 GroundCUA 上微调，桌面截图 9 类，含 Button），
本地放 `assets/models/yolo-ui.pt`。它随 `uitag` PyPI 包一起分发（包内 `uitag/models/yolo-ui.pt`），
不必走 gated 的 HF 仓库、也不用装该包那一堆 mlx-vlm / transformers 依赖。
模型在 640x640 tile 上训练，`imgsz` 留 640；仓库名带版本号但产物名不带，别按仓库名重命名。
它给出的是「这块是按钮/图标/文本…」，具体语义（保存还是取消）仍由区域内 OCR 文字给出，
所以本层不需要认识按钮含义——模型类别只需要能归到内部 kind：
button / icon / task / info / dialog / label。

模型实际输出 9 类（GroundCUA 微调）：Button / Menu / Input_Elements / Navigation /
Information_Display / Sidebar / Visual_Elements / Others / Unknown。类别名与内部 kind
不是一回事，检测后用 `CLASS_MAP` 归一化：Button、Input_Elements 归一成 button（可点），
其余归 info / icon 只读锚点；未收录的类别同样按 info 处理并只 warning 一次，
在 `yolo.class_map` 里补一行即可覆盖（换模型不用改代码）。

权重缺失时**不回退到 COCO 预训练模型**：COCO 的 person/car 当 UI 元素用只会坑死下游。
只有显式配置 allow_pretrained_fallback=true 时才保留该回退（调试用）。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

from ..core.log import get_logger, verbose

_log = get_logger("vision.yolo")

# 内部区域类型：只到「这是按钮/任务/信息/弹窗/图标」这一层，不含按钮语义
# 与 vision/screen.py 的 Region.kind 对齐；可点与否由 config 的 vision.clickable_kinds 决定
# 注意不包含 label：模型把 Text 类归一化成 label 后会被 ui_classes 过滤掉（文字本来就由
# OCR 层聚合成 label 区域，YOLO 再报一遍只是冗余，且会挡住兜底聚合）。
UI_CLASSES = ("button", "task", "info", "dialog", "icon")

# 模型类别名 -> 内部 kind。键在加载时统一归一化（小写、只留字母数字与汉字），
# 所以 "Text Input" / "text_input" / "textInput" 三种写法命中同一条。
# 约定：交互控件归 button（可点），装饰图归 icon，纯文字归 label，
# 容器/菜单栏/状态栏归 info（只读锚点，且大框不认领文字，见 screen.py）。
CLASS_MAP = {
    # 交互控件 -> button
    "button": "button", "btn": "button", "toolbarbutton": "button",
    "dialogbutton": "button", "pushbutton": "button", "link": "button",
    "hyperlink": "button", "tab": "button", "menuitem": "button",
    "checkbox": "button", "radiobutton": "button", "radio": "button",
    "switch": "button", "toggle": "button", "slider": "button",
    "dropdown": "button", "combobox": "button", "select": "button",
    "input": "button", "textinput": "button", "textfield": "button",
    "searchbox": "button", "spinner": "button", "picker": "button",
    "按钮": "button", "按键": "button", "链接": "button", "复选框": "button",
    "单选框": "button", "开关": "button", "滑块": "button", "下拉框": "button",
    "输入框": "button", "文本框": "button", "菜单项": "button",
    # 图标/图片 -> icon
    "icon": "icon", "logo": "icon", "image": "icon", "picture": "icon",
    "avatar": "icon", "thumbnail": "icon", "图标": "icon", "图片": "icon",
    # 文字 -> label（进 OCR 兜底层，可被 empty_clickable_fallback 提升为可点）
    "text": "label", "label": "label", "paragraph": "label", "title": "label",
    "textblock": "label", "caption": "label", "文字": "label", "文本": "label",
    # 弹窗 -> dialog（会被 game.py 的 _has_dialog 当成挡路弹窗）
    "dialog": "dialog", "modal": "dialog", "popup": "dialog", "alert": "dialog",
    "messagebox": "dialog", "弹窗": "dialog", "对话框": "dialog",
    # 容器/结构 -> info（只作锚点，不会被点）
    "window": "info", "panel": "info", "card": "info", "list": "info",
    "listview": "info", "table": "info", "tree": "info", "treeview": "info",
    "toolbar": "info", "menu": "info", "menubar": "info", "tabbar": "info",
    "navbar": "info", "navigation": "info", "statusbar": "info",
    "titlebar": "info", "progressbar": "info", "scrollbar": "info",
    "separator": "info", "divider": "info", "breadcrumb": "info",
    "accordion": "info", "group": "info", "tooltip": "info",
    "info": "info", "information": "info", "task": "task",
    "面板": "info", "窗口": "info", "列表": "info", "表格": "info", "菜单": "info",
    "工具栏": "info", "状态栏": "info", "标题栏": "info", "进度条": "info",
    "滚动条": "info", "信息": "info", "任务": "task",
}


def _norm(name: object) -> str:
    """类别名归一化：小写 + 只留字母数字与汉字（CJK 也是 alnum），兼容各种写法。"""
    return "".join(ch for ch in str(name or "").strip().lower() if ch.isalnum())


_KIND_MAP = {_norm(k): v for k, v in CLASS_MAP.items()}


def _lookup(label: object, extra_map: Optional[dict] = None) -> Optional[str]:
    """类别名 -> kind；未收录返回 None（调用方决定降级方式）。

    复合类名（`ToolbarButton` / `Icon_2` / `TableRow`）按三步匹配：
    精确 → **后缀** → 最长包含。后缀优先是因为 UI 类名通常「<修饰>+<控件>」
    （…Button 是按钮，…Icon 是图标），而纯按包含会让 `TableRow` 命中 `tab`
    被误判成可点按钮。两边都取最长，避免 `tab` 抢在 `table` 前面命中。
    """
    key = _norm(label)
    if not key:
        return None
    key = key.rstrip("0123456789") or key      # Icon_2 -> icon
    for k, v in (extra_map or {}).items():     # 配置映射优先于内置表
        if _norm(k) == key:
            return str(v).strip().lower()
    if key in _KIND_MAP:
        return _KIND_MAP[key]
    suffix = [t for t in _KIND_MAP if t and key.endswith(t)]
    if suffix:
        return _KIND_MAP[max(suffix, key=len)]
    inside = [t for t in _KIND_MAP if t and t in key]
    if inside:
        return _KIND_MAP[max(inside, key=len)]
    return None


def resolve_kind(label: object, extra_map: Optional[dict] = None) -> str:
    """模型类别名 -> 内部 kind；未收录按 info（只读锚点，最安全）。"""
    return _lookup(label, extra_map) or "info"


@dataclass
class Detection:
    label: str                              # 模型原始类别名（Button / Icon / Text …）
    confidence: float
    box: Tuple[int, int, int, int]           # x1, y1, x2, y2
    kind: str = ""                          # 归一化后的内部类型（button/icon/info…）
    layer: str = "ui"                       # ui=UITag 控件层 / general=通用检测层

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2


# INFO 下明细最多打这么多个框（行别太长）；DEBUG 下给全量，排查时才有完整数据
_LOG_ITEMS = 12


def _brief(dets: List["Detection"], limit: Optional[int] = None) -> str:
    """检测结果速记：`Button/button(0.38)@1180,70 box=(1102, 42, 1258, 98)`。

    limit=None 表示不截断（DEBUG 用）；超出 limit 时只补一句「另有 N 个」。
    """
    items = dets if limit is None else dets[:limit]
    parts = [
        f"{d.label}/{d.kind}({d.confidence:.2f})@{int(d.cx)},{int(d.cy)} box={d.box}"
        for d in items
    ]
    if len(items) < len(dets):
        parts.append(f"…另有 {len(dets) - len(items)} 个")
    return " | ".join(parts)


# 权重缓存（模块级）：ultralytics 加载 .pt 要读几十 MB 并建计算图，耗秒级。
# 而每个工具调用都会新建 BotContext（→ 新 YoloDetector），若把模型存在实例上，
# 就等于「每调一次工具重载一次权重」。实例只持有缓存里的同一个 model 对象。
_MODEL_CACHE: dict = {}      # 路径 -> YOLO 模型
_MODEL_ERRORS: dict = {}     # 路径 -> 加载失败原因（失败不重试，避免每次调用都卡住）
_WARNED_PATHS: set = set()   # 已提示过的权重路径（缺失/加载失败），进程内只提示一次
_WARNED_LABELS: set = set()  # 已提示过的未收录类别名，避免逐帧刷屏


class YoloDetector:
    """ultralytics YOLO 的薄封装；模型未配置时全部接口安全降级为空结果。"""

    def __init__(self, cfg: Optional[dict] = None):
        cfg = cfg or {}
        self.enabled = bool(cfg.get("enabled", False))
        self.model_path = cfg.get("model_path") \
            or "assets/models/yolo-ui.pt"
        self.conf = float(cfg.get("conf_threshold", 0.25))
        self.device = cfg.get("device", "cpu")
        # UI 层权重是在 640x640 tile 上训练的（GroundCUA），推理也用 640 才匹配训练分布：
        # 实测同一张游戏截图，640 的最高 conf 0.71、1280 只有 0.31，且 640 快一倍多。
        # 通用层（COCO 整图）不受此限，它自己在 general.imgsz 里配。
        self.imgsz = int(cfg.get("imgsz") or cfg.get("max_side") or 640)
        self.ui_classes = list(cfg.get("ui_classes") or UI_CLASSES)
        # 模型类别名 -> 内部 kind 的补充映射（覆盖 CLASS_MAP），换模型时只改配置
        self.class_map = dict(cfg.get("class_map") or {})
        # 未收录类别名的降级 kind：UITag 层用 info（只读锚点），通用层用 entity（游戏实体）
        self.default_kind = str(cfg.get("default_kind") or "info")
        # 显式配了 default_kind 就说明「未收录的都归这里」，不必再逐个类别告警
        # （通用层跑 COCO 时 80 类全未收录，逐个提示只是噪声）
        self._quiet_unknown = "default_kind" in cfg
        # 本检测器属于哪一层，写进 Detection 供上层区分（通用检测器构造时传 general）
        self.layer = str(cfg.get("layer") or "ui")
        # 只在调试时允许回退 COCO：默认关闭，否则 person/car 会被当成 UI 元素
        self.allow_pretrained_fallback = bool(cfg.get("allow_pretrained_fallback", False))
        self._model = None
        self._load_error: Optional[str] = None

    def _ensure_model(self):
        if self._model is not None or not self.enabled:
            return self._model
        try:
            path = self.model_path
            if path and not os.path.exists(path):
                if not self.allow_pretrained_fallback:
                    # UI 模式下权重缺失就禁用该层，交给 template/OCR 兜底
                    self.enabled = False
                    self._load_error = f"UI 权重不存在: {path}"
                    if path not in _WARNED_PATHS:
                        _WARNED_PATHS.add(path)
                        _log.warning("YOLO 权重 %s 不存在，UI 区域分类已禁用（不回退 COCO）",
                                     path)
                    return None
                if path not in _WARNED_PATHS:
                    _WARNED_PATHS.add(path)
                    _log.warning("权重 %s 不存在，按配置回退到 yolo11s.pt 预训练模型", path)
                path = "yolo11s.pt"
            key = os.path.abspath(path)
            model = _MODEL_CACHE.get(key)
            if model is None:
                if key in _MODEL_ERRORS:
                    raise RuntimeError(_MODEL_ERRORS[key])
                from ultralytics import YOLO
                try:
                    model = YOLO(path)
                except Exception as e:
                    _MODEL_ERRORS[key] = str(e)   # 依赖未装/权重损坏：不每帧重试
                    raise
                _MODEL_CACHE[key] = model
                names = [str(v) for v in (getattr(model, "names", {}) or {}).values()]
                _log.info("YOLO 模型已加载 %s（%d 类: %s）", path, len(names), names)
                unmapped = [n for n in names if _lookup(n, self.class_map) is None]
                if unmapped and not self._quiet_unknown:
                    _log.warning("YOLO 有 %d 个类别名未收录映射 %s，将按 %s 处理；"
                                 "需要改判定就在 class_map 里指定（如 {\"Checkbox\": \"button\"}）",
                                 len(unmapped), unmapped, self.default_kind)
            self._model = model
        except Exception as e:  # 依赖未装或权重损坏
            self.enabled = False
            self._load_error = str(e)
            if self.model_path not in _WARNED_PATHS:
                _WARNED_PATHS.add(self.model_path)
                _log.warning("YOLO 未启用（%s），跳过 UI 区域分类", e)
        return self._model

    @property
    def class_names(self) -> List[str]:
        """已加载模型支持的全部类别名（未加载/加载失败时为空列表，用于核对映射）。

        刻意不主动触发加载：`detect` 工具只跑其中一层时，另一层不该为此白读一次权重。
        """
        names = getattr(self._model, "names", None) or {}
        return [str(v) for v in names.values()]

    def detect_ui(self, img: np.ndarray, classes: Optional[List[str]] = None
                  ) -> List["Detection"]:
        """UI 区域分类：默认返回内部 kind 落在 ui_classes 里的检测（与模型类别名无关）。"""
        return self.detect(img, classes=list(classes or self.ui_classes))

    def detect(self, img: np.ndarray, classes: Optional[List[str]] = None
               ) -> List[Detection]:
        """跑一次推理。classes 既可写模型类别名（Button）也可写内部 kind（button）。"""
        if not self.enabled or self._ensure_model() is None:
            return []
        results = self._model.predict(img, conf=self.conf, device=self.device,
                                      imgsz=self.imgsz, verbose=False)
        wanted = {str(c).strip().lower() for c in classes} if classes else None
        detections: List[Detection] = []
        for r in results:
            names = r.names or {}
            for b in r.boxes:
                x1, y1, x2, y2 = (int(v) for v in b.xyxy[0].tolist())
                label = str(names.get(int(b.cls[0]), int(b.cls[0])))
                kind = _lookup(label, self.class_map)
                if kind is None:
                    kind = self.default_kind           # 未知类别只当锚点，绝不假设可点
                    if not self._quiet_unknown and label not in _WARNED_LABELS:
                        _WARNED_LABELS.add(label)       # 只提示一次（模块级），避免逐帧刷屏
                        _log.warning("YOLO 类别 %r 未收录映射，按 %s 处理；"
                                     "可在 class_map 里指定它的 kind", label, kind)
                # classes 同时匹配模型类别名与内部 kind，两种写法都能筛
                if wanted and label.strip().lower() not in wanted and kind not in wanted:
                    continue
                detections.append(Detection(label, float(b.conf[0]),
                                            (x1, y1, x2, y2), kind, self.layer))
        # 命中明细用 info：出问题时第一手要看的就是「这一帧到底框到了什么、置信度多少」。
        # 无检出不刷 info（安静场景每帧一行「没结果」只是噪声），DEBUG 才看。
        if detections:
            limit = None if verbose() else _LOG_ITEMS
            _log.info("YOLO[%s] 检出 %d 个 (conf>=%.2f imgsz=%d): %s",
                      self.layer, len(detections), self.conf, self.imgsz,
                      _brief(detections, limit))
        else:
            _log.debug("YOLO[%s] 无检出 (conf>=%.2f imgsz=%d)", self.layer, self.conf, self.imgsz)
        return detections

    def find(self, img: np.ndarray, label: str) -> Optional[Detection]:
        dets = self.detect(img, classes=[label])
        return max(dets, key=lambda d: d.confidence) if dets else None
