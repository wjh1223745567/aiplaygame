"""macOS 窗口枚举与定位（基于 Quartz / Cocoa，无需额外权限即可列出窗口）。"""
from __future__ import annotations

import os
import re
import signal
import time
from dataclasses import dataclass
from typing import List, Optional

import Quartz
from AppKit import NSRunningApplication, NSScreen, NSWorkspace

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
    pid: int = -1       # 属主进程 PID（用于激活到前台）
    onscreen: bool = True  # 是否在当前桌面上可见（最小化/其他桌面为 False）

    @property
    def cx(self) -> float:
        return self.x + self.width / 2

    @property
    def cy(self) -> float:
        return self.y + self.height / 2

    def contains(self, px: float, py: float) -> bool:
        return self.x <= px < self.x + self.width and self.y <= py < self.y + self.height

    def to_logical(self, px: float, py: float) -> tuple:
        """窗口内像素坐标 -> 全局逻辑点坐标（用于点击）。"""
        return self.x + px / self.scale, self.y + py / self.scale

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


def frontmost_app_name() -> str:
    """当前前台（顶层）应用名。"""
    try:
        app = NSWorkspace.sharedWorkspace().frontmostApplication()
        return str(app.localizedName()) if app else ""
    except Exception:  # noqa: BLE001
        return ""


def is_game_frontmost(win: WindowInfo) -> bool:
    """游戏是否已在顶层（前台）。"""
    front = frontmost_app_name()
    return bool(win.owner) and bool(front) and (win.owner in front or front in win.owner)


def activate_window(win: WindowInfo) -> bool:
    """把游戏窗口所属应用激活到前台（点击前保证输入落到游戏窗口）。

    优先按 PID 取 NSRunningApplication（该 pyobjc 版本无 runningApplications() 枚举）。
    返回是否发起成功（不代表已切完，需配合 ensure_front 轮询）。
    """
    try:
        if not (win.pid and win.pid > 0):
            return is_game_frontmost(win)
        ra = NSRunningApplication.runningApplicationWithProcessIdentifier_(win.pid)
        if ra is None:
            _log.debug("PID %d 无对应 NSRunningApplication，无法激活 %s", win.pid, win.owner)
            return False
        # NSApplicationActivateIgnoringOtherApps = 1<<1（macOS 13 上可抢占前台）
        ok = bool(ra.activateWithOptions_(1 << 1))
        _log.debug("激活窗口 pid=%d owner=%s -> %s", win.pid, win.owner, ok)
        return ok
    except Exception as e:  # noqa: BLE001
        _log.warning("激活窗口 %s 异常: %s", win.owner, e)
        return False


def request_quit(pid: int, stage: str = "quit") -> bool:
    """请求某个进程退出，stage 决定力度：

    quit（默认）走 NSRunningApplication.terminate()，等同 Cmd+Q，客户端能存档/正常下线；
    terminate 不可用（非 GUI 主进程、返回值 False）时退化为 SIGTERM。
    term 发 SIGTERM（进程默认处理就是退出，游戏很少拦截）。
    kill 发 SIGKILL，立即结束、客户端来不及存档。
    返回是否成功发出请求（不代表进程已退出，需配合轮询）。
    """
    if not (pid and pid > 0):
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        _log.debug("PID %d 已不存在，无需退出", pid)
        return True  # 已经没了
    except Exception as e:  # noqa: BLE001
        _log.warning("探测 PID %d 存活失败: %s", pid, e)
        return False
    if stage == "kill":
        try:
            os.kill(pid, signal.SIGKILL)
            _log.info("已发送 SIGKILL pid=%d", pid)
            return True
        except Exception as e:  # noqa: BLE001
            _log.warning("发送 SIGKILL 失败 pid=%d: %s", pid, e)
            return False
    if stage == "term":
        try:
            os.kill(pid, signal.SIGTERM)
            _log.info("已发送 SIGTERM pid=%d", pid)
            return True
        except Exception as e:  # noqa: BLE001
            _log.warning("发送 SIGTERM 失败 pid=%d: %s", pid, e)
            return False
    try:
        ra = NSRunningApplication.runningApplicationWithProcessIdentifier_(pid)
        if ra is not None and bool(ra.terminate()):
            _log.info("已请求正常退出（等同 Cmd+Q）pid=%d", pid)
            return True
    except Exception as e:  # noqa: BLE001
        _log.debug("terminate() 不可用 pid=%d: %s", pid, e)
    try:
        os.kill(pid, signal.SIGTERM)
        _log.info("正常退出请求不可用，改发 SIGTERM pid=%d", pid)
        return True
    except Exception as e:  # noqa: BLE001
        _log.warning("退出请求全部失败 pid=%d: %s", pid, e)
        return False


def alive_pids(pids: List[int]) -> List[int]:
    """过滤出仍然存活的 PID（os.kill(pid,0) 探活，不发送信号）。"""
    alive: List[int] = []
    for pid in pids:
        try:
            os.kill(pid, 0)
            alive.append(pid)
        except ProcessLookupError:
            continue
        except PermissionError:
            alive.append(pid)
        except Exception:  # noqa: BLE001
            continue
    return alive


def ensure_front(win: WindowInfo, timeout: float = 1.2, poll: float = 0.05) -> bool:
    """若游戏不在顶层则自动切到顶层；返回操作后是否处于顶层。

    已在顶层时仅一次轻量查询即返回（热路径开销可忽略）；需切换时
    采用“发起激活 + 短轮询 + 重试”，避免系统切换延迟导致的单次回读误判。
    """
    if is_game_frontmost(win):
        return True
    _log.debug("窗口不在前台，尝试激活 %s", win.owner)
    deadline = time.time() + timeout
    while time.time() < deadline:
        activate_window(win)
        end = time.time() + 0.3
        while time.time() < end:
            if is_game_frontmost(win):
                return True
            time.sleep(poll)
    front = is_game_frontmost(win)
    if not front:
        _log.warning("无法把窗口切到前台（后续输入可能落不到游戏）: %s", win)
    return front
