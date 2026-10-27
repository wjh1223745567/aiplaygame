"""中文 OCR：以 RapidOCR（PaddleOCR 模型的 ONNX 实现）为主引擎。

输出统一为 OcrResult 列表：文本、置信度、窗口内像素坐标框与中心点。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Tuple

import cv2
import numpy as np

from ..core.log import get_logger, verbose

_log = get_logger("vision.ocr")


@dataclass
class OcrResult:
    text: str
    confidence: float
    box: Tuple[int, int, int, int]  # x1, y1, x2, y2 （像素）

    @property
    def cx(self) -> float:
        return (self.box[0] + self.box[2]) / 2

    @property
    def cy(self) -> float:
        return (self.box[1] + self.box[3]) / 2

    def __str__(self) -> str:
        return f"{self.text!r}({self.confidence:.2f}) @({self.cx:.0f},{self.cy:.0f}) box={self.box}"


_engine = None

# INFO 下明细最多打这么多条（行别太长）；DEBUG 下给全量，排查时才有完整数据
_LOG_ITEMS = 12


def _brief(results: List[OcrResult], limit: Optional[int] = None) -> str:
    """识别结果速记：`'师门任务'(0.98)@120,340`。

    limit=None 表示不截断（DEBUG 用）；超出 limit 时只补一句「另有 N 条」。
    """
    items = results if limit is None else results[:limit]
    parts = [f"{r.text!r}({r.confidence:.2f})@{int(r.cx)},{int(r.cy)}"
             for r in items]
    if len(items) < len(results):
        parts.append(f"…另有 {len(results) - len(items)} 条")
    return " | ".join(parts)


def _get_engine():
    global _engine
    if _engine is None:
        from rapidocr_onnxruntime import RapidOCR
        _engine = RapidOCR()
    return _engine


def _preprocess(img: np.ndarray, upscale: float = 1.0) -> np.ndarray:
    """可选放大，对小字号文本提升识别率。"""
    if upscale and upscale != 1.0:
        img = cv2.resize(img, None, fx=upscale, fy=upscale, interpolation=cv2.INTER_CUBIC)
    return img


def ocr_image(img: np.ndarray, min_confidence: float = 0.5,
              region: Optional[Tuple[int, int, int, int]] = None,
              upscale: float = 1.0) -> List[OcrResult]:
    """对整图（或 region=(x1,y1,x2,y2) 裁剪区）做 OCR，坐标换算回原图。"""
    full_h, full_w = img.shape[:2]          # 裁剪会改 shape，日志里要的是原图尺寸
    offset_x = offset_y = 0
    if region:
        x1, y1, x2, y2 = region
        offset_x, offset_y = x1, y1
        img = img[y1:y2, x1:x2]
    img = _preprocess(img, upscale)

    engine = _get_engine()
    output, _ = engine(img)
    results: List[OcrResult] = []
    for item in output or []:
        quad, text, conf = item[0], item[1], float(item[2])
        if conf < min_confidence or not text.strip():
            continue
        xs = [p[0] for p in quad]
        ys = [p[1] for p in quad]
        box = (int(min(xs)) + offset_x, int(min(ys)) + offset_y,
               int(max(xs)) + offset_x, int(max(ys)) + offset_y)
        if upscale != 1.0:
            box = tuple(int(v / upscale) for v in box)  # type: ignore
        results.append(OcrResult(text=text.strip(), confidence=conf, box=box))  # type: ignore
    # 整图 OCR 每帧只跑一次、内容是排查第一手依据 -> info；
    # 框内小区域 OCR（每个模板框一次）数量级大得多 -> 只在 DEBUG 下打。
    log = _log.info if region is None else _log.debug
    if results:
        limit = None if verbose() else _LOG_ITEMS
        log("OCR %dx%d region=%s min_conf=%.2f 命中 %d 条: %s",
            full_w, full_h, region, min_confidence, len(results), _brief(results, limit))
    else:
        _log.debug("OCR %dx%d region=%s min_conf=%.2f 无文字",
                   full_w, full_h, region, min_confidence)
    return results
