"""YOLO 目标检测（可选模块）。

默认关闭。当 OCR + 模板匹配无法覆盖动态目标（NPC/怪物/掉落物）时，
可训练 YOLO11 模型并将权重放到 assets/models/，配置里 enabled=true 即接入。
未提供自定义权重时自动回退到官方预训练 yolo11s.pt（首次使用自动下载）。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np


@dataclass
class Detection:
    label: str
    confidence: float
    box: Tuple[int, int, int, int]  # x1, y1, x2, y2

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2


class YoloDetector:
    """ultralytics YOLO 的薄封装；模型未配置时全部接口安全降级为空结果。"""

    def __init__(self, cfg: Optional[dict] = None):
        cfg = cfg or {}
        self.enabled = bool(cfg.get("enabled", False))
        self.model_path = cfg.get("model_path", "yolo11s.pt") or "yolo11s.pt"
        self.conf = float(cfg.get("conf_threshold", 0.5))
        self.device = cfg.get("device", "cpu")
        self._model = None
        self._load_error: Optional[str] = None

    def _ensure_model(self):
        if self._model is not None or not self.enabled:
            return self._model
        try:
            from ultralytics import YOLO
            # 自定义权重不存在时，回退到官方预训练 YOLO11s（ultralytics 自动下载）
            import os
            path = self.model_path
            if path and not os.path.exists(path):
                print(f"[YOLO] 权重 {path} 不存在，回退到 yolo11s.pt 预训练模型")
                path = "yolo11s.pt"
            self._model = YOLO(path)
        except Exception as e:  # 依赖未装或权重缺失
            self.enabled = False
            self._load_error = str(e)
            print(f"[YOLO] 未启用（{e}），跳过动态目标检测")
        return self._model

    def detect(self, img: np.ndarray, classes: Optional[List[str]] = None
               ) -> List[Detection]:
        if not self.enabled or self._ensure_model() is None:
            return []
        results = self._model.predict(img, conf=self.conf, device=self.device, verbose=False)
        detections: List[Detection] = []
        for r in results:
            names = r.names or {}
            for b in r.boxes:
                x1, y1, x2, y2 = (int(v) for v in b.xyxy[0].tolist())
                label = str(names.get(int(b.cls[0]), int(b.cls[0])))
                if classes and label not in classes:
                    continue
                detections.append(Detection(label, float(b.conf[0]), (x1, y1, x2, y2)))
        return detections

    def find(self, img: np.ndarray, label: str) -> Optional[Detection]:
        dets = self.detect(img, classes=[label])
        return max(dets, key=lambda d: d.confidence) if dets else None
