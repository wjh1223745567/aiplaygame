"""人工接管保护：鼠标被人移动/点击后暂停全部输入，静止满 idle_resume_sec 后自动恢复。

## 为什么需要「程序动作计数」

本模块监听的是**全局**鼠标事件，而程序自己 `InputController.click()` 也是
一步一步写 `MouseController.position`（12 步插值），会被监听器当成「人在动鼠标」。
若只按「有没有鼠标事件」判断，程序一移动鼠标就会把自己暂停死，而且永远恢复不了。

所以区分靠两层：

1. `program_action()` 上下文管理器：进入时 `_prog_active += 1`，退出时减一。
   用**计数**而不是布尔：`click()` 内部还要调 `_move_to_raw()`，两层嵌套必须都能归零。
2. `program_grace_sec` 宽限期：程序动作结束后这段时间内的事件仍然丢弃，
   吸收插值尾巴与系统异步补发的事件（pynput 的 move 回调不保证同帧送达）。

## `wait_if_paused()` 必须在 `program_action()` 之前

否则暂停等待期间 `_prog_active > 0`，监听器会一直把人工事件当程序动作丢掉，
`_last_human` 永远不刷新 → 永远不恢复 → 死锁。

## 键盘不包 program_action

键盘事件不会产生鼠标回调，包进去只会把「打字期间用户接管」也一起屏蔽掉，
所以 `press_key` / `hotkey` / `type_text` 只调 `wait_if_paused()`。

## generation

每次「进入暂停」和「恢复」各 +1。上层（`vision/llm.step_once`）在计算前记一次、
点击前再比一次：不相等说明中途被人工接管过，暂停前的截图与坐标全部作废，必须重新感知。
"""
from __future__ import annotations

import threading
import time
from contextlib import contextmanager
from typing import Optional

from .log import get_logger

_log = get_logger("core.human_guard")

DEFAULT_IDLE_RESUME_SEC = 10.0   # 鼠标静止多久后自动恢复
DEFAULT_PROGRAM_GRACE_SEC = 0.6  # 程序动作结束后多久内的事件视为程序尾巴
DEFAULT_MAX_BLOCK_SEC = 30.0     # 工具入口最多为等待恢复阻塞多久
_POLL_SEC = 0.2                  # 等待恢复的轮询间隔


class HumanGuard:
    """人为操作检测与自动暂停。进程内单例，通过 `get_guard()` 取得。"""

    def __init__(self, cfg: Optional[dict] = None):
        self.enabled = True
        self.idle_resume_sec = DEFAULT_IDLE_RESUME_SEC
        self.program_grace_sec = DEFAULT_PROGRAM_GRACE_SEC
        self.max_pause_sec = 0.0        # >0 时限制单次暂停总时长（0=不限）
        self.max_block_sec = DEFAULT_MAX_BLOCK_SEC
        self.watch_clicks = True
        self.configure(cfg)

        self._lock = threading.RLock()
        self._prog_active = 0           # 程序自身进行中的写操作数
        self._last_prog_ts = 0.0        # 最近一次程序动作结束时刻（monotonic）
        self._last_human = 0.0          # 最近一次人为事件时刻（monotonic）
        self._paused_since = 0.0        # 本轮暂停开始时刻；0 表示当前未暂停
        self._last_span = 0.0           # 上一轮暂停持续了多少秒（供恢复日志用）
        self._generation = 0
        self._listener = None
        self._started = False

    # ---------- 配置 ----------
    def configure(self, cfg: Optional[dict]) -> None:
        """按 config 的 human_guard 段覆盖默认值；缺字段一律保留原值。"""
        if not cfg:
            return
        self.enabled = bool(cfg.get("enabled", self.enabled))
        self.idle_resume_sec = float(cfg.get("idle_resume_sec", self.idle_resume_sec))
        self.program_grace_sec = float(cfg.get("program_grace_sec", self.program_grace_sec))
        self.max_pause_sec = float(cfg.get("max_pause_sec", self.max_pause_sec))
        self.max_block_sec = float(cfg.get("max_block_sec", self.max_block_sec))
        self.watch_clicks = bool(cfg.get("watch_clicks", self.watch_clicks))

    # ---------- 监听 ----------
    def start(self) -> bool:
        """启动鼠标监听（幂等）。返回是否处于监听状态。"""
        if not self.enabled:
            _log.info("人工接管保护未启用（human_guard.enabled=false）")
            return False
        if not self._accessibility_ok():
            # 没有辅助功能权限时，合成的键鼠本身也点不动，二者同生共死
            _log.warning(
                "无 macOS「辅助功能/输入监控」权限：人工接管保护不生效"
                "（同时鼠标键盘也无法被程序驱动）；"
                "请在系统设置 → 隐私与安全性中勾选运行本进程的 App 后重启")
            return False
        with self._lock:
            if self._started:
                return True
        try:
            from pynput import mouse
            listener = mouse.Listener(on_move=self._on_move, on_click=self._on_click)
            listener.daemon = True
            listener.start()
        except Exception as e:  # noqa: BLE001 监听失败不能拖垮主流程
            _log.warning("鼠标监听启动失败（%s）：人工接管保护不生效", e)
            return False
        self._listener = listener
        with self._lock:
            self._started = True
        _log.info("人工接管保护已启用：鼠标被移动/点击即暂停，静止 %.0fs 后自动恢复",
                  self.idle_resume_sec)
        return True

    def stop(self) -> None:
        listener, self._listener = self._listener, None
        with self._lock:
            self._started = False
        if listener is not None:
            try:
                listener.stop()
            except Exception:  # noqa: BLE001
                pass

    @staticmethod
    def _accessibility_ok() -> bool:
        """macOS 辅助功能权限；非 macOS 或查询失败时不阻断。"""
        try:
            from ApplicationServices import AXIsProcessTrusted
            return bool(AXIsProcessTrusted())
        except Exception:  # noqa: BLE001
            return True

    # ---------- 监听回调（跑在监听线程，只做时间戳赋值）----------
    def _on_move(self, _x, _y) -> None:
        self._mark_human()

    def _on_click(self, _x, _y, _button, pressed) -> None:
        # 「鼠标不动直接点一下」是最常见的接管动作，只测移动会漏
        if pressed and self.watch_clicks:
            self._mark_human()

    def _mark_human(self) -> None:
        now = time.monotonic()
        entered = False
        with self._lock:
            if not self.enabled:
                return
            # 程序动作中，或刚结束未过宽限期：这是程序自己的尾巴，丢弃
            if self._prog_active > 0 or now - self._last_prog_ts < self.program_grace_sec:
                return
            self._last_human = now
            if self._paused_since <= 0:
                self._paused_since = now
                self._generation += 1
                entered = True
        if entered:
            _log.warning("检测到人工接管（鼠标移动/点击），自动化已暂停；"
                         "鼠标静止 %.0fs 后自动恢复", self.idle_resume_sec)

    # ---------- 程序动作标记 ----------
    @contextmanager
    def program_action(self):
        """标记「接下来的鼠标事件是程序自己产生的」，期间与宽限期内的回调全部丢弃。"""
        with self._lock:
            self._prog_active += 1
        try:
            yield
        finally:
            with self._lock:
                self._prog_active -= 1
                self._last_prog_ts = time.monotonic()

    # ---------- 状态 ----------
    @property
    def paused(self) -> bool:
        """当前是否因人工接管而暂停。暂停自然结束时顺带递增 generation。"""
        if not self.enabled:
            return False
        now = time.monotonic()
        with self._lock:
            if self._paused_since <= 0:
                return False
            if now - self._last_human >= self.idle_resume_sec:
                self._last_span = now - self._paused_since
                self._paused_since = 0.0
                self._generation += 1
                return False
            return True

    @property
    def generation(self) -> int:
        """暂停代次：进入暂停与恢复各 +1，用于判断「算出的结果是否已过期」。"""
        with self._lock:
            return self._generation

    def pause_elapsed(self) -> float:
        """本轮暂停已持续多少秒（未暂停返回 0）。"""
        with self._lock:
            return max(0.0, time.monotonic() - self._paused_since) if self._paused_since > 0 else 0.0

    def resume_in(self) -> float:
        """距离自动恢复还剩多少秒（未暂停返回 0）。"""
        if not self.paused:
            return 0.0
        with self._lock:
            return max(0.0, self.idle_resume_sec - (time.monotonic() - self._last_human))

    # ---------- 等待 ----------
    def wait_if_paused(self, max_block_sec: float = 0.0) -> bool:
        """暂停中则阻塞等待恢复。

        恢复返回 True；被急停、或等待超过 max_block_sec（0=不限）返回 False，
        调用方据此直接放弃本次动作（绝不带着「用户正在操作」的状态去点鼠标）。
        """
        if not self.paused:
            return True
        from . import input as _input  # 延迟导入：input 顶层依赖本模块，不能形成环

        limit = max(0.0, float(max_block_sec or 0.0))
        started = time.monotonic()
        while self.paused:
            if _input.is_stopped():
                _log.warning("等待人工操作结束时被急停，放弃本次动作")
                return False
            if limit > 0 and time.monotonic() - started >= limit:
                _log.warning("人工操作持续超过 %.0fs，放弃等待（本次动作不执行）", limit)
                return False
            if self.max_pause_sec > 0 and self.pause_elapsed() >= self.max_pause_sec:
                _log.warning("单次暂停已达 %.0fs 上限（max_pause_sec），强制恢复执行",
                             self.max_pause_sec)
                with self._lock:
                    self._last_span = self.pause_elapsed()
                    self._last_human = 0.0
                    self._paused_since = 0.0
                    self._generation += 1
                break
            time.sleep(_POLL_SEC)
        _log.info("人工操作结束，已暂停 %.1fs，恢复执行", max(self._last_span, 0.0))
        return True


_GUARD: Optional[HumanGuard] = None
_GUARD_LOCK = threading.Lock()


def get_guard(cfg: Optional[dict] = None) -> HumanGuard:
    """进程级单例。首次调用按 cfg 初始化；之后提供的 cfg 只用于更新参数。"""
    global _GUARD
    with _GUARD_LOCK:
        if _GUARD is None:
            _GUARD = HumanGuard(cfg)
        else:
            _GUARD.configure(cfg)
        return _GUARD
