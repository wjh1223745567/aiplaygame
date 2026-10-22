"""中文 OCR：以 RapidOCR（PaddleOCR 模型的 ONNX 实现）为主引擎。

输出统一为 OcrResult 列表：文本、置信度、窗口内像素坐标框与中心点。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import List, Optional, Tuple

import cv2
import numpy as np


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
    return results


def find_text(img: np.ndarray, keyword: str, exact: bool = False,
              min_confidence: float = 0.5,
              region: Optional[Tuple[int, int, int, int]] = None) -> Optional[OcrResult]:
    """在当前画面中查找包含 keyword 的文本，返回置信度最高的一个。"""
    return match_text(ocr_image(img, min_confidence=min_confidence, region=region),
                      keyword, exact=exact)


def match_text(results: List[OcrResult], keyword: str,
               exact: bool = False) -> Optional[OcrResult]:
    """在已识别结果列表中查找 keyword（避免对同一图重复 OCR）。"""
    target = _normalize(keyword)
    best: Optional[OcrResult] = None
    for r in results:
        norm = _normalize(r.text)
        if (norm == target) if exact else (target in norm):
            if best is None or r.confidence > best.confidence:
                best = r
    return best


def match_all_text(results: List[OcrResult], keyword: str) -> List[OcrResult]:
    """在已识别结果列表中查找所有匹配 keyword 的文本块。"""
    target = _normalize(keyword)
    return [r for r in results if target in _normalize(r.text)]


def find_all_text(img: np.ndarray, keyword: str, **kw) -> List[OcrResult]:
    """查找所有匹配关键字的文本块。"""
    target = _normalize(keyword)
    return [r for r in ocr_image(img, **kw) if target in _normalize(r.text)]


def _normalize(text: str) -> str:
    """去掉空白与常见全角/半角差异，便于稳定匹配。"""
    text = re.sub(r"\s+", "", text)
    return text.translate(str.maketrans("（）：，０１２３４５６７８９", "():,0123456789"))
