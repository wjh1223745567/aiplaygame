"""失败案例收集：把「可点候选为空」的真实画面存档，作为按钮检测训练集的原料。

为什么「失败即采集」：训练数据里最有价值的是当前识别链的盲区——商城/背包/对话框/
文案变化的列表行，这些界面不会被人工截图自然覆盖（人只截想看的界面）。让数据分布
由真实失败场景决定，比预先设计一份采集清单更不容易漏掉界面。

去重按「图像感知哈希」：8x8 均值哈希（64 位）+ 汉明距离 <= 12 视为同一画面。
为什么不用画面文本集合（`screen_key`）做键：游戏里聊天频道/任务追踪一直在滚，
实测同一弹窗的相邻 4 帧文本 Jaccard 只有 0.50-0.56，拿来当键会把同一画面当成
新画面反复存；而图像哈希实测更干净：同画面帧间距离 0-6（动画帧、角色走动、时间戳），
不同画面 13-24——13 是「对话框 vs 切场景 splash」这种结构相近的一对，余量并不宽，
哪天发现同一画面被存了两张，先查是不是踩到了这个边界。文本集合仍写进 index.jsonl
供后续分层采样。

重启后去重仍生效：首次采集时从 index.jsonl 恢复已知哈希（旧记录没有 ahash 字段的
从图片重算）。上限 `vision.max_failed_shots`（默认 300）、开关
`vision.collect_failed_shots`（默认开）。
旁路安全：整个函数 try/except 包住，任何失败只 warning，绝不影响决策主流程。
"""
from __future__ import annotations

import json
import os
import time
from typing import List, Optional

import cv2
import numpy as np

from ..core.capture import save_image
from ..core.log import get_logger
from .screen import Region, screen_key

_log = get_logger("vision.collect")

_HASH_SIDE = 8          # 哈希边长：8x8 = 64 位
_DUP_BITS = 12          # 汉明距离上限：超过它才算另一个画面
_INDEX = "index.jsonl"

# 进程内已存过的图像哈希：同一画面连续判空时不再重复写盘
_seen: List[int] = []
_loaded = False
# 上限提醒只打一次（否则停滞画面每步都刷一条 warning）
_limit_warned = False


def _ahash(img: np.ndarray) -> int:
    """8x8 均值哈希：缩到 8x8 灰度，亮于均值的位记 1。"""
    if img.ndim == 3:
        code = cv2.COLOR_BGRA2GRAY if img.shape[2] == 4 else cv2.COLOR_BGR2GRAY
        gray = cv2.cvtColor(img, code)
    else:
        gray = img
    small = cv2.resize(gray, (_HASH_SIDE, _HASH_SIDE), interpolation=cv2.INTER_AREA)
    bits = (small > small.mean()).astype(np.uint8).flatten()
    return int(np.packbits(bits).tobytes().hex(), 16)


def _is_dup(h: int) -> bool:
    return any(bin(h ^ s).count("1") <= _DUP_BITS for s in _seen)


def _load_seen(out_dir: str) -> None:
    """从 index.jsonl 恢复历史哈希（重启后不重存同画面）。只跑一次。"""
    global _loaded
    _loaded = True
    path = os.path.join(out_dir, _INDEX)
    if not os.path.isfile(path):
        return
    for line in open(path, encoding="utf-8"):
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        h = rec.get("ahash")
        if h is None:                      # 旧记格式：从图片重算
            img = cv2.imread(rec.get("path") or "")
            h = _ahash(img) if img is not None else None
        if h is not None and h not in _seen:
            _seen.append(int(h))
    _log.debug("失败案例去重基线恢复 %d 个画面指纹", len(_seen))


def collect_failed_shot(cfg: dict, img: np.ndarray, regions: List[Region],
                        goal: str = "") -> Optional[str]:
    """可点候选为空的画面存档到 <debug_dir>/failed_shots/，返回落盘路径（未收集为 None）。

    落盘两张东西：<时间戳>_<key8>.png 与追加的 index.jsonl（ts / goal / 区域数 /
    文字数 / screen_key 列表 / 路径），后者供后续按界面分层采样、复查漏收集。
    """
    global _limit_warned
    try:
        vcfg = (cfg or {}).get("vision", {}) or {}
        if not vcfg.get("collect_failed_shots", True):
            return None
        max_n = int(vcfg.get("max_failed_shots", 300))
        out_dir = os.path.join((cfg or {}).get("capture", {}).get("debug_dir", "debug"),
                               "failed_shots")
        if not _loaded:
            _load_seen(out_dir)
        h = _ahash(img)
        if _is_dup(h):
            return None
        # 上限按磁盘现存量算：重启进程后依旧生效
        saved = 0
        if os.path.isdir(out_dir):
            saved = sum(1 for n in os.listdir(out_dir) if n.endswith(".png"))
        if saved >= max_n:
            if not _limit_warned:
                _limit_warned = True
                _log.warning("失败案例已达上限 %d 张，停止收集"
                             "（调大 vision.max_failed_shots 可继续）", max_n)
            return None
        texts = sorted(screen_key(regions))
        key = f"{h:016x}"[:8]
        # 先记指纹再写盘：磁盘故障时同一画面也只尝试一次，不把日志刷满
        _seen.append(h)
        ts = time.strftime("%Y%m%d_%H%M%S")
        path = save_image(img, os.path.join(out_dir, f"{ts}_{key}.png"))
        n_text = sum(1 for r in regions if r.text)
        with open(os.path.join(out_dir, _INDEX), "a", encoding="utf-8") as f:
            f.write(json.dumps({"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "goal": goal,
                                "key": key, "ahash": h, "regions": len(regions),
                                "texts": n_text, "screen_key": texts, "path": path},
                               ensure_ascii=False) + "\n")
        _log.info("失败案例已存档 %s（%d 区域 / %d 条文字）", path, len(regions), n_text)
        return path
    except Exception as e:  # noqa: BLE001
        _log.warning("失败案例收集失败（不影响主流程）: %s", e)
        return None