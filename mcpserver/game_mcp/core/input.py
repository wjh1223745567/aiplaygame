"""鼠标/键盘输入控制（pynput 实现，需要「辅助功能」权限）。

所有对外接口接收的都是**窗口内像素坐标**，内部自动换算成全局逻辑点坐标。
带随机偏移与随机延迟，模拟人类操作节奏。
"""
from __future__ import annotations

import random
import time
from typing import Optional

from pynput import keyboard as kb
from pynput.mouse import Button as MouseButton
from pynput.mouse import Controller as MouseController

from .log import get_logger
from .window import WindowInfo, ensure_front

_log = get_logger("core.input")

_STOP_FLAG = {"stopped": False}
_DRY_RUN = {"enabled": False}


def set_dry_run(enabled: bool) -> None:
    """干跑模式：只识别不执行任何鼠标/键盘动作。"""
    _DRY_RUN["enabled"] = enabled


def is_dry_run() -> bool:
    return _DRY_RUN["enabled"]


def has_accessibility() -> bool:
    """macOS 辅助功能（合成键鼠事件）权限是否已授予当前进程。

    非 macOS 或导入失败时返回 True（不阻断）。
    """
    try:
        from ApplicationServices import AXIsProcessTrusted
        return bool(AXIsProcessTrusted())
    except Exception:  # noqa: BLE001
        return True


def check_accessibility_or_warn() -> bool:
    """真机执行前自检：无权限时打印醒目提示。返回是否已授权。"""
    if is_dry_run() or has_accessibility():
        return True
    _log.warning(
        "未获得 macOS「辅助功能」权限：鼠标/键盘操作会被系统静默丢弃"
        "（点了没反应、快捷键无效、鼠标不动）\n"
        "  解决：系统设置 → 隐私与安全性 → 辅助功能，勾选运行本脚本的 App"
        "（IDE 集成终端里运行时通常需勾选该 IDE）\n"
        "  授权后必须完全退出并重启该 App 才会生效")
    return False

_HOTKEYS = {
    "esc": kb.Key.esc, "escape": kb.Key.esc,
    "enter": kb.Key.enter, "return": kb.Key.enter,
    "space": kb.Key.space, "tab": kb.Key.tab,
    "backspace": kb.Key.backspace, "delete": kb.Key.delete,
    "up": kb.Key.up, "down": kb.Key.down, "left": kb.Key.left, "right": kb.Key.right,
    "f10": kb.Key.f10, "f11": kb.Key.f11, "f12": kb.Key.f12,
}


class InputController:
    def __init__(self, win: WindowInfo, cfg: Optional[dict] = None):
        self.win = win
        cfg = cfg or {}
        self.offset = cfg.get("click_random_offset", 4)
        self.delay_min = cfg.get("action_delay_min", 0.35)
        self.delay_max = cfg.get("action_delay_max", 0.9)
        self.move_duration = cfg.get("move_duration_sec", 0.45)
        self._mouse = MouseController()

    # ---------- 坐标换算 ----------
    def to_global(self, px: float, py: float) -> tuple:
        return self.win.to_logical(px, py)

    # ---------- 人类化延迟 ----------
    def human_delay(self, scale: float = 1.0) -> None:
        lo, hi = self.delay_min * scale, self.delay_max * scale
        if is_stopped():
            return
        time.sleep(random.uniform(lo, hi))

    # ---------- 前台保障 ----------
    def _ensure_front(self) -> bool:
        """动作前确保游戏在顶层：不在顶层则自动切换（点击/按键才落到游戏窗口）。

        前台判定很轻量（一次 NSWorkspace 查询），仅在确实需要切换时才激活+短暂等待。
        返回是否真的切到了前台——键盘事件是全局的，窗口没在前台时会落到别的应用，
        调用方必须看返回值决定要不要继续。
        """
        try:
            return ensure_front(self.win)
        except Exception:  # noqa: BLE001
            return False

    def frontmost(self) -> bool:
        """确保游戏窗口在前台并返回是否成功（外部调用方用）。"""
        return self._ensure_front()

    # ---------- 鼠标 ----------
    def move_to(self, px: float, py: float) -> None:
        gx, gy = self.to_global(px, py)
        cur = self._mouse.position
        steps = 12
        start = time.time()
        for i in range(1, steps + 1):
            if is_stopped():
                return
            t = i / steps
            t = t * t * (3 - 2 * t)  # smoothstep 缓动
            self._mouse.position = (
                cur[0] + (gx - cur[0]) * t,
                cur[1] + (gy - cur[1]) * t,
            )
            time.sleep(max(self.move_duration / steps, 0.005))
        self._mouse.position = (gx, gy)

    def click(self, px: float, py: float, button: str = "left",
              double: bool = False, move_first: bool = True) -> None:
        if is_stopped():
            return
        if is_dry_run():
            _log.info("dry-run 点击 (%d,%d) %s", px, py, button)
            return
        self._ensure_front()
        jx = px + random.uniform(-self.offset, self.offset)
        jy = py + random.uniform(-self.offset, self.offset)
        if move_first:
            self.move_to(jx, jy)
        btn = MouseButton.left if button == "left" else MouseButton.right
        self._mouse.click(btn, count=2 if double else 1)
        _log.info("点击 (%d,%d) %s%s", px, py, button, " 双击" if double else "")

    def drag(self, p1: tuple, p2: tuple) -> None:
        if is_stopped():
            return
        if is_dry_run():
            _log.info("dry-run 拖拽 (%d,%d) -> (%d,%d)", *p1, *p2)
            return
        self.move_to(*p1)
        self._mouse.press(MouseButton.left)
        # 分两段移动，避免长距离瞬移
        mid = ((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
        self.move_to(*mid)
        self.move_to(*p2)
        time.sleep(random.uniform(0.05, 0.15))
        self._mouse.release(MouseButton.left)
        _log.info("拖拽 (%d,%d) -> (%d,%d)", *p1, *p2)

    # ---------- 键盘 ----------
    def press_key(self, key: str) -> bool:
        if is_stopped():
            return False
        if is_dry_run():
            _log.info("dry-run 按键 %s", key)
            return True
        # 键盘事件是全局的：窗口不在前台时不发，否则会打到用户当前正在用的应用上
        if not self._ensure_front():
            _log.warning("按键 %s 未发送：游戏窗口不在前台，避免落到其他应用", key)
            return False
        k = _HOTKEYS.get(key.lower())
        if k is None and len(key) == 1:
            k = key.lower()
        if k is None:
            _log.warning("按键 %r 不支持，已忽略", key)
            return False
        self._tap(k)
        _log.info("按键 %s", key)
        return True

    def press_enter(self) -> None:
        self._tap(kb.Key.enter)

    def press_esc(self) -> None:
        self._tap(kb.Key.esc)

    def press_tab(self) -> None:
        self._tap(kb.Key.tab)

    def hotkey(self, *keys: str) -> None:
        """组合键，如 hotkey('cmd', 'v')。"""
        if is_stopped():
            return
        if is_dry_run():
            _log.info("dry-run 组合键 %s", "+".join(keys))
            return
        self._ensure_front()
        mapped = []
        for key in keys:
            k = key.lower()
            if k in ("cmd", "command"):
                mapped.append(kb.Key.cmd)
            elif k in ("ctrl", "control"):
                mapped.append(kb.Key.ctrl)
            elif k in ("alt", "option"):
                mapped.append(kb.Key.alt)
            elif k in ("shift",):
                mapped.append(kb.Key.shift)
            else:
                mapped.append(k)
        controller = self._keyboard()
        for k in mapped:
            controller.press(k)
        for k in reversed(mapped):
            controller.release(k)
        _log.info("组合键 %s", "+".join(keys))

    def type_text(self, text: str) -> None:
        """逐字符输入（ASCII 可靠；中文建议先走剪贴板 paste）。"""
        if is_stopped():
            return
        if is_dry_run():
            _log.info("dry-run 输入 %r", text)
            return
        controller = self._keyboard()
        for ch in text:
            if ch == "\n":
                controller.press(kb.Key.enter)
                controller.release(kb.Key.enter)
            else:
                controller.type(ch)
            time.sleep(random.uniform(0.03, 0.1))
        self.human_delay()

    def paste_text(self, text: str) -> None:
        """通过剪贴板粘贴中文文本。"""
        import subprocess
        process = subprocess.Popen(["pbcopy"], stdin=subprocess.PIPE)
        process.communicate(text.encode("utf-8"))
        self.hotkey("cmd", "v")

    def _keyboard(self):
        return kb.Controller()

    def _tap(self, key) -> None:
        kb.Controller().tap(key)
        self.human_delay(scale=0.4)
        _log.debug("tap %s", key)


# ---------- 全局急停 ----------
def is_stopped() -> bool:
    return _STOP_FLAG["stopped"]


def stop() -> None:
    _STOP_FLAG["stopped"] = True


def start_stop_listener(hotkey: str = "f12") -> Optional[kb.GlobalHotKeys]:
    """注册全局急停热键（默认 F12）。"""
    key = _HOTKEYS.get(hotkey.lower())
    if key is None:
        return None
    try:
        listener = kb.GlobalHotKeys({str(key): _on_stop})
        listener.daemon = True
        listener.start()
        return listener
    except Exception:
        return None


def _on_stop() -> None:
    _STOP_FLAG["stopped"] = True
    _log.warning("检测到急停热键，自动化已停止")
