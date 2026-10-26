"""mcpserver 运行日志：统一出口，写项目根的 `mcpserver.log`，同时按需回显到 stderr。

为什么不用 print：stdio 传输下 **stdout 就是 MCP 协议通道**，任何 print 都会把
JSON-RPC 流冲散（host 侧表现为「解析失败 / 连接断开」）。所以这里的日志只走
文件与 stderr 两条路。

- 文件：项目根 `mcpserver.log`（与 ai-decisions.log 并列），默认 INFO，
  超过 5MB 轮转为 `mcpserver.log.1`（覆盖上一份）。
- stderr：默认只出 WARNING 及以上（避免刷脏 host 控制台），
  前台排查时用 `AUTOGAME_LOG_STDERR=INFO` 打开全部。
- 级别：`AUTOGAME_LOG_LEVEL=DEBUG` 可看到截图/OCR/输入的每一步细节。
- 目录：`AUTOGAME_ROOT` 可整体搬走日志目录（与 host 侧定位一致）。
"""
from __future__ import annotations

import json
import logging
import os
import sys
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path

# core/log.py -> core -> game_mcp -> mcpserver -> 项目根
PROJECT_ROOT = str(Path(__file__).resolve().parents[3])
FILE_NAME = "mcpserver.log"
MAX_BYTES = 5 * 1024 * 1024
DATEFMT = "%Y-%m-%d %H:%M:%S"

_lock = threading.Lock()
_configured = False


class _BriefFormatter(logging.Formatter):
    """把 `game_mcp.tools.game` 之类压成 `tools.game`：日志里模块名够用就行。"""

    def format(self, record: logging.LogRecord) -> str:
        name = record.name
        record.short = name[len("game_mcp."):] if name.startswith("game_mcp.") else name
        return super().format(record)


def log_path() -> Path:
    """日志文件位置：AUTOGAME_ROOT 优先，否则项目根。"""
    env = os.environ.get("AUTOGAME_ROOT")
    base = Path(env) if env and Path(env).is_dir() else Path(PROJECT_ROOT)
    return base / FILE_NAME


def _level(name: str, default: int) -> int:
    return getattr(logging, (name or "").strip().upper(), default)


def setup(level: str | None = None) -> None:
    """初始化根 logger（幂等）：文件 handler + stderr handler + 崩溃钩子。"""
    global _configured
    with _lock:
        if _configured:
            return
        _configured = True

        root = logging.getLogger("game_mcp")
        root.setLevel(logging.DEBUG)  # 由各 handler 自己控级别
        root.propagate = False
        fmt = _BriefFormatter("%(asctime)s.%(msecs)03d %(levelname)-5s [%(short)s] %(message)s",
                              DATEFMT)

        try:
            path = log_path()
            path.parent.mkdir(parents=True, exist_ok=True)
            fh = RotatingFileHandler(path, maxBytes=MAX_BYTES, backupCount=1,
                                     encoding="utf-8")
            fh.setLevel(_level(level or os.environ.get("AUTOGAME_LOG_LEVEL"), logging.INFO))
            fh.setFormatter(fmt)
            root.addHandler(fh)
        except OSError:
            pass  # 日志目录不可写时降级为只出 stderr，不影响服务

        sh = logging.StreamHandler(sys.stderr)
        sh.setLevel(_level(os.environ.get("AUTOGAME_LOG_STDERR"), logging.WARNING))
        sh.setFormatter(fmt)
        root.addHandler(sh)

        _install_hooks()


def _install_hooks() -> None:
    """未捕获异常也留痕：stdio 下崩溃时终端可能什么信息都没有。"""
    def hook(exc_type, exc, tb):
        if issubclass(exc_type, KeyboardInterrupt):
            return
        logging.getLogger("game_mcp.crash").critical(
            "未捕获异常，进程退出", exc_info=(exc_type, exc, tb))

    sys.excepthook = hook
    threading.excepthook = lambda a: hook(a.exc_type, a.exc_value, a.exc_traceback)


def get_logger(name: str) -> logging.Logger:
    """取模块 logger；name 可写短名（server / input / vision.ocr）。"""
    setup()
    return logging.getLogger(name if name.startswith("game_mcp") else f"game_mcp.{name}")


def verbose() -> bool:
    """当前是否会真正输出 DEBUG。

    ``logger.isEnabledFor(DEBUG)`` 在这里没用：root 恒为 DEBUG，真正的过滤在 handler
    上（文件默认 INFO、stderr 默认 WARNING），所以得直接看 handler 级别。
    用于「DEBUG 打全量、INFO 只打摘要」这类分级输出。
    """
    setup()
    return any(h.level <= logging.DEBUG for h in logging.getLogger("game_mcp").handlers)


def brief(value, limit: int = 400) -> str:
    """把参数/结果压成单行短文本，避免一条日志被大对象冲爆。"""
    try:
        text = json.dumps(value, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        text = repr(value)
    text = text.replace("\r", "").replace("\n", " ")
    if len(text) <= limit:
        return text
    return f"{text[:limit]}…(+{len(text) - limit})"
