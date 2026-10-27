"""macOS 窗口枚举与定位（基于 Quartz / Cocoa，无需额外权限即可列出窗口）。

只负责「找到窗口」：列窗口、按标题关键字匹配。点击、置前、退出这些动作能力
随动作层一起移除了，需要时再往这一层加。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import List, Optional

import Quartz
from AppKit import NSScreen

from .log import get_logger

_log = get_logger("core.window")


@dataclass
class WindowInfo:
    """一个屏幕上可见的窗口。坐标均为全局屏幕坐标（左上原点，_points_ 逻辑坐标系）。"""
    window_id: int
    owner: str
    title: str
    x: float
    y: float
    width: float
    height: float
    scale: float = 1.0  # Retina 缩放（截图像素 / 逻辑点）
    pid: int = -1       # 属主进程 PID
    onscreen: bool = True  # 是否在当前桌面上可见（最小化/其他桌面为 False）

    @property
    def cx(self) -> float:
        return self.x + self.width / 2

    @property
    def cy(self) -> float:
        return self.y + self.height / 2

    def __str__(self) -> str:
        return (f"[{self.window_id}] {self.owner!r} - {self.title!r} "
                f"@({self.x:.0f},{self.y:.0f} {self.width:.0f}x{self.height:.0f} scale={self.scale})")


def _copy_string_cf(value) -> str:
    try:
        return str(value) if value is not None else ""
    except Exception:
        return ""


def list_windows(include_offscreen: bool = False) -> List[WindowInfo]:
    """枚举窗口。

    include_offscreen=False 只返回当前桌面上可见的窗口（可截图）；
    include_offscreen=True 连最小化/其他桌面的窗口一并返回（仅用于判断“是否已打开”）。
    """
    if include_offscreen:
        opts = (Quartz.kCGWindowListOptionAll
                | Quartz.kCGWindowListExcludeDesktopElements)
    else:
        opts = (Quartz.kCGWindowListOptionOnScreenOnly
                | Quartz.kCGWindowListExcludeDesktopElements)
    raw = Quartz.CGWindowListCopyWindowInfo(opts, Quartz.kCGNullWindowID)
    main_scale = float(NSScreen.mainScreen().backingScaleFactor()) if _has_retina() else 1.0

    windows: List[WindowInfo] = []
    for w in raw or []:
        layer = w.get("kCGWindowLayer", 0)
        if layer != 0:  # 只保留普通应用窗口
            continue
        name = _copy_string_cf(w.get("kCGWindowOwnerName"))
        title = _copy_string_cf(w.get("kCGWindowName"))
        bounds = w.get("kCGWindowBounds") or {}
        wid = int(w.get("kCGWindowNumber", -1))
        pid = int(w.get("kCGWindowOwnerPID", -1))
        width = float(bounds.get("Width", 0))
        height = float(bounds.get("Height", 0))
        if width < 64 or height < 64:
            continue
        windows.append(WindowInfo(
            window_id=wid, owner=name, title=title,
            x=float(bounds.get("X", 0)), y=float(bounds.get("Y", 0)),
            width=width, height=height, scale=main_scale, pid=pid,
            onscreen=bool(w.get("kCGWindowIsOnscreen")) if include_offscreen else True,
        ))
    return windows


def _has_retina() -> bool:
    try:
        return NSScreen.mainScreen().backingScaleFactor() > 1
    except Exception:
        return False


def find_game_window(title_keywords: List[str], exclude_keywords: Optional[List[str]] = None,
                     owner_hint: Optional[str] = None,
                     include_offscreen: bool = False) -> Optional[WindowInfo]:
    """按标题关键字匹配游戏窗口，返回面积最大的候选。

    include_offscreen=True 时可匹配最小化/其他桌面上的窗口（此时不保证能截图）。
    """
    exclude_keywords = exclude_keywords or []
    candidates = []
    windows = list_windows(include_offscreen=include_offscreen)
    for w in windows:
        text = f"{w.owner} {w.title}"
        if not any(k and k in text for k in title_keywords):
            continue
        if any(k and k in text for k in exclude_keywords):
            continue
        if owner_hint and owner_hint not in w.owner:
            continue
        candidates.append(w)
    if not candidates:
        _log.debug("未匹配到游戏窗口 关键词=%s include_offscreen=%s 枚举到 %d 个窗口",
                   title_keywords, include_offscreen, len(windows))
        return None
    # 若窗口无标题（部分客户端不设置 kCGWindowName），优先按属主进程名匹配
    best = max(candidates, key=lambda c: c.width * c.height)
    _log.debug("匹配到游戏窗口 %s（候选 %d 个）", best, len(candidates))
    return best
