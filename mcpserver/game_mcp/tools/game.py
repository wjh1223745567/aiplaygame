"""游戏功能 · 启动与登录。

open_game / login 均返回 JSON 友好的 dict（供 MCP server 直接透出）。
定位一律走 OCR 文字，不依赖模板图。
"""
from __future__ import annotations

import subprocess
import time

from ..core.engine import BotContext
from ..core.window import activate_window, find_game_window
from ..vision.ocr import ocr_image

# 客户端与关键文案（问道 macOS 客户端）
GAME_APP = "/Applications/问道.app"
# 进程名/路径关键字：问道是 iOS 包装应用，真实进程名为 AsktaoMobile iOS
GAME_PROCESS_HINTS = ("AsktaoMobile", "问道")
LOGIN_BUTTON = "登录游戏"
# 主场景 HUD 关键字（任一命中即视为已进入游戏世界）
MAIN_SCENE_HINTS = ["商城", "包裹", "任务", "自动", "地图", "经验"]
# 加载/排队阶段的提示文案（出现时说明服务区组尚未就绪）
LOADING_HINTS = ["登录中", "排队", "加载", "连接服务器", "正在连接"]


def _window(cfg: dict, include_offscreen: bool = False):
    wc = cfg.get("window", {})
    return find_game_window(wc.get("title_keywords", []), wc.get("exclude_keywords"),
                            include_offscreen=include_offscreen)


def _game_pids() -> list[int]:
    """游戏进程 PID 列表（窗口最小化/在其他桌面时窗口枚举看不到，只能靠进程判断）。"""
    pids: set[int] = set()
    for hint in GAME_PROCESS_HINTS:
        try:
            out = subprocess.run(["pgrep", "-fl", hint],
                                 capture_output=True, text=True, timeout=5)
        except Exception:  # noqa: BLE001
            continue
        for line in out.stdout.splitlines():
            pid, _, cmd = line.partition(" ")
            if not pid.isdigit():
                continue
            # `open /Applications/问道.app`、pgrep 自身也会命中关键字，需排除
            if cmd.startswith("open ") or "pgrep" in cmd:
                continue
            pids.add(int(pid))
    return sorted(pids)


def is_game_running(cfg: dict) -> dict:
    """检测游戏是否已打开：窗口可见 / 窗口存在但不可见 / 仅进程在跑 / 完全没启动。"""
    win = _window(cfg)
    hidden = None if win is not None else _window(cfg, include_offscreen=True)
    pids = _game_pids()
    found = win or hidden
    return {
        "running": found is not None or bool(pids),
        "window_visible": win is not None,
        "window_hidden": hidden is not None,
        "pids": pids,
        "window": str(found) if found is not None else None,
    }


def _ocr(cfg: dict, img):
    return ocr_image(img, min_confidence=cfg.get("ocr", {}).get("min_confidence", 0.5))


def open_game(cfg: dict, wait_timeout: float = 90.0) -> dict:
    """启动或激活问道客户端并轮询等待游戏窗口可见。

    三种已打开的情形分开处理：
      · 窗口可见 -> 直接返回；
      · 窗口存在但不可见（最小化/在其他桌面）-> 只尝试激活，并缩短等待，避免无意义长等；
      · 完全未启动 -> `open` 启动后轮询等待。
    """
    win = _window(cfg)
    if win is not None:
        return {"ok": True, "already_running": True, "launched": False, "window": str(win)}

    hidden = _window(cfg, include_offscreen=True)
    pids = _game_pids()
    running = hidden is not None or bool(pids)
    try:
        if hidden is not None:
            activate_window(hidden)  # macOS 13 不保证能跨桌面还原，仍按下面逻辑轮询
        else:
            subprocess.Popen(["open", GAME_APP])
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": f"启动客户端失败: {e}"}

    # 已打开但窗口不可显现时，等太久没意义
    wait = min(wait_timeout, 10.0) if hidden is not None else wait_timeout
    end = time.time() + wait
    while time.time() < end:
        win = _window(cfg)
        if win is not None:
            return {"ok": True, "already_running": running, "launched": not running,
                    "window": str(win)}
        time.sleep(1.0)
    if hidden is not None:
        return {"ok": False, "already_running": True,
                "error": "游戏已打开，但窗口不可见（最小化或在其他桌面），请手动切到前台后重试"}
    if running:
        return {"ok": False, "already_running": True,
                "error": f"客户端进程已在运行，但 {wait:.0f}s 内未出现窗口（可能仍在启动），请稍后重试"}
    return {"ok": False, "error": f"等待窗口超时（{wait:.0f}s）：客户端可能未启动成功"}


def login(cfg: dict, timeout: float = 120.0, auto_open: bool = True,
          open_timeout: float = 90.0) -> dict:
    """自动检测游戏是否已打开 -> 点击「登录游戏」-> 等待进入主场景。

    - 已打开：直接登录；已在主场景则跳过点击，返回 already_in_game
    - 未打开：auto_open=True 时自动启动客户端（等待 open_timeout 秒）后继续登录
    """
    win, launched, err = _ensure_game_window(cfg, auto_open=auto_open,
                                             open_timeout=open_timeout)
    if win is None:
        return err
    ctx = BotContext(cfg, win)

    # 0) 已经在主场景（HUD 关键字命中）就不必再点登录
    if _in_main_scene(ctx, cfg):
        return {"ok": True, "stage": "main_scene", "already_in_game": True,
                "launched": launched, "window": str(win)}

    # 1) 等待登录按钮出现，且加载/排队文案消失（服务区组加载完成）
    if not _wait_ready(ctx, cfg, timeout):
        return {"ok": False, "stage": "wait_ready", "launched": launched,
                "error": "登录画面未就绪（服务区组仍在加载或未出现「登录游戏」按钮）"}

    # 2) 点击「登录游戏」
    if not ctx.click_text(LOGIN_BUTTON, timeout=5):
        return {"ok": False, "stage": "click_login", "launched": launched,
                "error": f"未点击到「{LOGIN_BUTTON}」按钮"}

    # 3) 等待进入主场景（HUD 关键字出现）
    entered = _wait_main_scene(ctx, cfg, timeout=timeout)
    if not entered:
        return {"ok": False, "stage": "clicked_login", "launched": launched,
                "error": "已点击登录但未检测到主场景 HUD，可能需手动选角/排队"}
    return {"ok": True, "stage": "main_scene", "launched": launched, "window": str(win)}


def _ensure_game_window(cfg: dict, auto_open: bool, open_timeout: float):
    """返回 (win, launched, err)。未打开时按 auto_open 决定是否自动拉起客户端。"""
    win = _window(cfg)
    if win is not None:
        return win, False, None
    if not auto_open:
        state = is_game_running(cfg)
        if state["window_hidden"]:
            reason = "已打开，但窗口不可见（最小化或在其他桌面）"
        elif state["running"]:
            reason = "已打开，但窗口尚未创建"
        else:
            reason = "未启动"
        return None, False, {"ok": False, "stage": "detect",
                             "error": f"未找到游戏窗口（客户端{reason}），且 auto_open=False"}
    opened = open_game(cfg, wait_timeout=open_timeout)
    if not opened.get("ok"):
        return None, False, {"ok": False, "stage": "open_game",
                             "error": opened.get("error", "启动客户端失败")}
    win = _window(cfg)
    if win is None:
        return None, False, {"ok": False, "stage": "open_game",
                             "error": "客户端已启动，但未检测到游戏窗口"}
    return win, bool(opened.get("launched")), None


def _wait_ready(ctx: BotContext, cfg: dict, timeout: float) -> bool:
    """轮询直到「登录游戏」出现且无加载类文案（判定服务区组已加载完成）。"""
    end = time.time() + timeout
    while time.time() < end:
        img = ctx.grab()
        results = _ocr(cfg, img)
        texts = [r.text for r in results]
        has_login = any(LOGIN_BUTTON in t for t in texts)
        loading = any(any(h in t for h in LOADING_HINTS) for t in texts)
        if has_login and not loading:
            return True
        time.sleep(1.0)
    return False


def _in_main_scene(ctx: BotContext, cfg: dict) -> bool:
    """单次截图 OCR 判断是否已在主场景。"""
    texts = [r.text for r in _ocr(cfg, ctx.grab())]
    return any(any(h in t for h in MAIN_SCENE_HINTS) for t in texts)


def _wait_main_scene(ctx: BotContext, cfg: dict, timeout: float) -> bool:
    """轮询直到出现主场景 HUD 关键字之一。"""
    end = time.time() + timeout
    while time.time() < end:
        if _in_main_scene(ctx, cfg):
            return True
        time.sleep(1.5)
    return False
