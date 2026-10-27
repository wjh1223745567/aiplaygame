"""输入层护栏的回归用例：窗口不在前台时，一个事件都不许发出去。

鼠标/键盘事件是全局的。窗口没切到前台就发，会打到用户正在用的别的应用上；
对游戏本身也毫无作用，只会让模型以为「点了没反应」而反复重试。
press_key 早就拦住了，click/drag/hotkey/type_text 曾经只是「试图切前台」却照样发。
"""
import types

import support  # noqa: F401  （把 mcpserver 放进 sys.path）
from game_mcp.core import input as user_input


class FakeMouse:
    def __init__(self):
        self.position = (0, 0)
        self.clicks = []
        self.buttons = []

    def click(self, button, count=1):
        self.clicks.append((button, count))

    def press(self, button):
        self.buttons.append(("press", button))

    def release(self, button):
        self.buttons.append(("release", button))


class FakeKeyboard:
    def __init__(self):
        self.typed = []
        self.keys = []

    def type(self, ch):
        self.typed.append(ch)

    def press(self, key):
        self.keys.append(("press", key))

    def release(self, key):
        self.keys.append(("release", key))

    def tap(self, key):
        self.keys.append(("tap", key))


def _controller(front: bool):
    """构造控制器：窗口换算用假实现，前台切换结果按需指定。"""
    win = types.SimpleNamespace(to_logical=lambda x, y: (x + 1000, y + 1000))
    ctrl = user_input.InputController(win, {"action_delay_min": 0, "action_delay_max": 0,
                                            "move_duration_sec": 0})
    ctrl._mouse = FakeMouse()
    ctrl._keyboard = lambda: FakeKeyboard()
    ctrl._ensure_front = lambda: front
    user_input.set_dry_run(False)
    return ctrl


def test_click_not_sent_when_window_not_frontmost():
    ctrl = _controller(front=False)
    assert ctrl.click(10, 20) is False
    assert ctrl._mouse.clicks == [], "窗口不在前台却发出了点击"


def test_click_sent_once_when_frontmost():
    ctrl = _controller(front=True)
    assert ctrl.click(10, 20) is True
    assert len(ctrl._mouse.clicks) == 1


def test_drag_not_sent_when_window_not_frontmost():
    ctrl = _controller(front=False)
    assert ctrl.drag((10, 20), (30, 40)) is False
    assert ctrl._mouse.buttons == [], "窗口不在前台却按下了鼠标键"


def test_hotkey_not_sent_when_window_not_frontmost():
    ctrl = _controller(front=False)
    kb = FakeKeyboard()
    ctrl._keyboard = lambda: kb
    assert ctrl.hotkey("cmd", "v") is False
    assert kb.keys == [], "窗口不在前台却发了组合键"


def test_type_text_not_sent_when_window_not_frontmost():
    ctrl = _controller(front=False)
    kb = FakeKeyboard()
    ctrl._keyboard = lambda: kb
    assert ctrl.type_text("hello") is False
    assert kb.typed == [], "窗口不在前台却开始打字"


def test_press_key_still_blocked():
    ctrl = _controller(front=False)
    kb = FakeKeyboard()
    ctrl._keyboard = lambda: kb
    assert ctrl.press_key("esc") is False
    assert kb.keys == []
