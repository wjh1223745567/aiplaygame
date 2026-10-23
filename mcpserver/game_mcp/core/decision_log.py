"""大模型判断日志：把每次模型返回后的原始输出与判断结果追加到项目根的 ai-decisions.log。

host（Java 侧 `DecisionLog`）写的是同一份文件，用 `source=host` / `source=mcpserver`
区分，这样「对话里模型选了哪个工具」和「游戏内模型点了哪个选项」能在一条时间线上对照。

日志属旁路观察：写失败只在终端提示一次，绝不打断决策主流程；文件超过 5MB 时
轮转为 `ai-decisions.log.1`（覆盖上一份）。
"""
from __future__ import annotations

import os
import threading
import time
from pathlib import Path
from typing import List

from ..config import PROJECT_ROOT
from .log import get_logger

_log = get_logger("core.decision_log")

FILE_NAME = "ai-decisions.log"
MAX_BYTES = 5 * 1024 * 1024
RULE = "─" * 72

_lock = threading.Lock()
_warned = False


def log_path() -> Path:
    """日志路径：AUTOGAME_ROOT 优先，否则项目根（与 host 侧定位一致）。"""
    env = os.environ.get("AUTOGAME_ROOT")
    if env and Path(env).is_dir():
        return Path(env) / FILE_NAME
    return Path(PROJECT_ROOT) / FILE_NAME


def stamp() -> str:
    """形如 `2026-10-23 10:20:31.402` 的时间戳，和 Java 侧格式一致。"""
    now = time.time()
    return (time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now))
            + f".{int((now % 1) * 1000):03d}")


def write(lines: List[str]) -> None:
    """追加一块日志（调用方给多行，首行自带时间戳与 source）。"""
    global _warned
    text = RULE + "\n" + "\n".join(lines).rstrip() + "\n"
    path = log_path()
    try:
        with _lock:
            path.parent.mkdir(parents=True, exist_ok=True)
            try:
                if path.stat().st_size > MAX_BYTES:
                    path.replace(path.with_name(FILE_NAME + ".1"))
            except FileNotFoundError:
                pass
            with path.open("a", encoding="utf-8") as fh:
                fh.write(text)
    except Exception as e:  # noqa: BLE001 日志不能影响决策
        if not _warned:  # 同一个进程只提示一次，避免刷屏
            _warned = True
            _log.warning("写判断日志失败（%s）: %s", path, e)


def one_line(text, limit: int = 1200) -> str:
    """日志一行一事：压平换行并截断长文本。"""
    s = "" if text is None else str(text)
    s = s.replace("\r", "").replace("\n", " / ").strip()
    return s if len(s) <= limit else s[:limit] + "…(已截断)"
