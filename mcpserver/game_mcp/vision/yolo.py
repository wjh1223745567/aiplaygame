"""通用目标检测（可选模块）：COCO 预训练模型找游戏实体，只作只读锚点。

它不是「UI 控件分类器」：`assets/models/yolo11s.pt` 是 COCO 80 类，配置里用
`classes: ["person"]` 白名单只留人物（COCO 的 tv/car/键盘对游戏没用，不筛只是噪声）。
白名单外的类别（classes 留空时才是全 80 类）一律按 `default_kind` 处理，现为 entity
——只读锚点，永远不进可点候选，也不认领框内文字（血条旁的怪物名照样进 label 层）。

权重缺失或依赖未装时该层直接禁用并记 `_load_error`：少了一半只读锚点，模板、
面板彩色选项与 OCR 兜底都不受影响。
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

from ..core.log import get_logger, verbose

_log = get_logger("vision.yolo")


@dataclass
class Detection:
    label: str                              # 模型原始类别名（person / …）
    confidence: float
    box: Tuple[int, int, int, int]           # x1, y1, x2, y2
    kind: str = ""                          # 归一化后的内部类型（entity）
    layer: str = "general"                  # 只保留通用检测层（COCO）

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2


# INFO 下明细最多打这么多个框（行别太长）；DEBUG 下给全量，排查时才有完整数据
_LOG_ITEMS = 12


def _brief(dets: List["Detection"], limit: Optional[int] = None) -> str:
    """检测结果速记：`person/entity(0.38)@1180,70 box=(1102, 42, 1258, 98)`。

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


class YoloDetector:
    """ultralytics YOLO 的薄封装；模型未配置时全部接口安全降级为空结果。"""

    def __init__(self, cfg: Optional[dict] = None):
        cfg = cfg or {}
        self.enabled = bool(cfg.get("enabled", False))
        self.model_path = str(cfg.get("model_path") or "assets/models/yolo11s.pt")
        self.conf = float(cfg.get("conf_threshold", 0.35))
        self.device = cfg.get("device", "cpu")
        # COCO 整图推理：分辨率越高越找得到远处的小目标，没有 tile 训练分布的约束
        self.imgsz = int(cfg.get("imgsz") or cfg.get("max_side") or 1280)
        # 全部检测结果的 kind：通用层只找实体
        self.default_kind = str(cfg.get("default_kind") or "entity")
        self.layer = str(cfg.get("layer") or "general")
        self._model = None
        self._load_error: Optional[str] = None

    def _ensure_model(self):
        if self._model is not None or not self.enabled:
            return self._model
        try:
            path = self.model_path
            if path and not os.path.exists(path):
                # 权重缺失就禁用该层，交给模板/选项/OCR 兜底（不回退别的模型）
                self.enabled = False
                self._load_error = f"检测权重不存在: {path}"
                if path not in _WARNED_PATHS:
                    _WARNED_PATHS.add(path)
                    _log.warning("YOLO 权重 %s 不存在，通用检测层已禁用（少一半实体锚点，其余各层不受影响）",
                                 path)
                return None
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
                _log.info("YOLO 模型已加载 %s（%d 类: %s）", path, len(names), names[:12])
            self._model = model
        except Exception as e:  # 依赖未装或权重损坏
            self.enabled = False
            self._load_error = str(e)
            if self.model_path not in _WARNED_PATHS:
                _WARNED_PATHS.add(self.model_path)
                _log.warning("YOLO 未启用（%s），跳过通用检测层", e)
        return self._model

    @property
    def class_names(self) -> List[str]:
        """已加载模型支持的全部类别名（未加载/加载失败时为空列表，用于核对配置）。

        刻意不主动触发加载：`detect` 工具不跑这一层时不该为此白读一次权重。
        """
        names = getattr(self._model, "names", None) or {}
        return [str(v) for v in names.values()]

    def detect(self, img: np.ndarray, classes: Optional[List[str]] = None
               ) -> List[Detection]:
        """跑一次推理。classes 为模型类别名白名单（不区分大小写），空表示全类别。"""
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
                if wanted and label.strip().lower() not in wanted:
                    continue
                detections.append(Detection(label, float(b.conf[0]),
                                            (x1, y1, x2, y2), self.default_kind, self.layer))
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
