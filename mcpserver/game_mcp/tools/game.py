"""游戏功能 · 启动、登录与关闭。

open_game / login / close_game 均返回 JSON 友好的 dict（供 MCP server 直接透出）。
定位一律走 OCR 文字，不依赖模板图。
"""
from __future__ import annotations

import subprocess
import time

from ..core.engine import BotContext
from ..core.log import get_logger
from ..core.window import activate_window, alive_pids, find_game_window, request_quit
from ..vision.ocr import ocr_image

_log = get_logger("tools.game")

# 客户端与关键文案（问道 macOS 客户端）
GAME_APP = "/Applications/问道.app"
# 进程名/路径关键字：问道是 iOS 包装应用，真实进程名为 AsktaoMobile iOS
GAME_PROCESS_HINTS = ("AsktaoMobile", "问道")
LOGIN_BUTTONS = ["进入游戏", "登录游戏"]  # 不同版本客户端文案不同，长的优先
# 主场景 HUD 关键字（任一命中即视为已进入游戏世界）
MAIN_SCENE_HINTS = ["商城", "包裹", "任务", "自动", "地图", "经验"]
# 加载/排队阶段的提示文案（出现时说明服务区组尚未就绪）
LOADING_HINTS = ["登录中", "排队", "加载", "连接服务器", "正在连接"]
# 登录前的提示弹窗（公告 / 设备风险提示 / 协议更新）：“确定”类按钮，长的放前面优先命中
DIALOG_CONFIRM_BUTTONS = ["我知道了", "知道了", "确定", "确认"]
# 同一个按钮最多点几次，防止弹窗关不掉时死磕
DIALOG_MAX_CLICKS = 3


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
    _log.debug("is_game_running: 可见窗口=%s 隐藏窗口=%s pids=%s", win, hidden, pids)
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
        _log.info("open_game: 窗口已可见，无需启动 %s", win)
        return {"ok": True, "already_running": True, "launched": False, "window": str(win)}

    hidden = _window(cfg, include_offscreen=True)
    pids = _game_pids()
    running = hidden is not None or bool(pids)
    try:
        if hidden is not None:
            _log.info("open_game: 窗口存在但不可见，尝试激活 %s", hidden)
            activate_window(hidden)  # macOS 13 不保证能跨桌面还原，仍按下面逻辑轮询
        else:
            _log.info("open_game: 启动客户端 %s", GAME_APP)
            subprocess.Popen(["open", GAME_APP])
    except Exception as e:  # noqa: BLE001
        _log.error("open_game: 启动客户端失败: %s", e)
        return {"ok": False, "error": f"启动客户端失败: {e}"}

    # 已打开但窗口不可显现时，等太久没意义
    wait = min(wait_timeout, 10.0) if hidden is not None else wait_timeout
    _log.info("open_game: 等待窗口出现，最多 %.0fs（已在运行=%s）", wait, running)
    end = time.time() + wait
    while time.time() < end:
        win = _window(cfg)
        if win is not None:
            _log.info("open_game: 窗口已出现 %s（耗时 %.1fs）", win, wait - (end - time.time()))
            return {"ok": True, "already_running": running, "launched": not running,
                    "window": str(win)}
        time.sleep(1.0)
    if hidden is not None:
        _log.warning("open_game: 游戏已开但窗口仍不可见（最小化或在其他桌面）")
        return {"ok": False, "already_running": True,
                "error": "游戏已打开，但窗口不可见（最小化或在其他桌面），请手动切到前台后重试"}
    if running:
        _log.warning("open_game: 进程在跑但 %.0fs 内未出现窗口", wait)
        return {"ok": False, "already_running": True,
                "error": f"客户端进程已在运行，但 {wait:.0f}s 内未出现窗口（可能仍在启动），请稍后重试"}
    _log.warning("open_game: 等待窗口超时（%.0fs），客户端可能未启动成功", wait)
    return {"ok": False, "error": f"等待窗口超时（{wait:.0f}s）：客户端可能未启动成功"}


def close_game(cfg: dict, timeout: float = 8.0, force: bool = False) -> dict:
    """关闭问道客户端并等待进程退出。

    分三级升级（每级都等进程真的退完再决定要不要升级），因为该客户端实测会忽略 Cmd+Q：
      1) 正常退出（等同 Cmd+Q，能存档/正常下线），等 `timeout` 秒；
      2) SIGTERM，等 3 秒；
      3) SIGKILL 强杀，等 5 秒（来不及存档）。
    `force=True` 跳过前两级直接强杀。本来就没在跑时返回 already_closed。
    窗口在（含最小化/其他桌面）或进程在都算「在跑」，因为两者可能不同步。
    返回值：closed 已结束的 PID、killed 被强杀的 PID、forced 是否用到了强杀。
    """
    win = _window(cfg, include_offscreen=True)
    pids = set(_game_pids())
    if win is not None and win.pid > 0:
        pids.add(win.pid)
    targets = sorted(pids)
    if not targets:
        _log.info("close_game: 未发现运行中的游戏进程，无需关闭")
        return {"ok": True, "already_closed": True, "closed": [], "killed": [],
                "forced": False, "window": None}
    _log.info("close_game: 目标进程 %s force=%s timeout=%.0fs", targets, force, timeout)

    if force:
        _log.warning("close_game: force=True，直接 SIGKILL %s（客户端来不及存档）", targets)
        for pid in targets:
            request_quit(pid, stage="kill")
        still = _wait_exit(targets, 5.0)
        return _close_result(targets, still, killed=targets, forced=True)

    for pid in targets:
        request_quit(pid)
    remaining = _wait_exit(targets, timeout)
    if remaining:
        _log.warning("close_game: %s 未在 %.0fs 内退出，升级 SIGTERM", remaining, timeout)
        for pid in remaining:
            request_quit(pid, stage="term")
        remaining = _wait_exit(remaining, 3.0)
    if not remaining:
        return _close_result(targets, [], killed=[], forced=False)

    _log.warning("close_game: %s 仍未退出，升级 SIGKILL", remaining)
    for pid in remaining:
        request_quit(pid, stage="kill")
    still = _wait_exit(remaining, 5.0)
    return _close_result(targets, still, killed=remaining, forced=True)


def _close_result(targets: list[int], still: list[int], killed: list[int],
                  forced: bool) -> dict:
    """组装关闭结果；still 非空说明连强杀都没能结束，需要人工处理。"""
    closed = sorted(set(targets) - set(still))
    if still:
        _log.error("close_game: 强制结束 %s 后仍未退出，需人工处理", still)
        return {"ok": False, "stage": "close", "closed": closed, "killed": killed,
                "remaining": still, "error": f"强制结束 {still} 后仍未退出，请手动关闭"}
    _log.info("close_game: 已关闭 %s（killed=%s forced=%s）", closed, killed, forced)
    return {"ok": True, "already_closed": False, "closed": closed, "killed": killed,
            "forced": forced, "window": None}


def _wait_exit(pids: list[int], timeout: float) -> list[int]:
    """轮询等待进程退出，返回超时后仍存活的 PID（全退光则为空）。"""
    end = time.time() + timeout
    while time.time() < end:
        if not alive_pids(pids):
            return []
        time.sleep(0.5)
    return alive_pids(pids)


def login(cfg: dict, timeout: float = 120.0, auto_open: bool = True,
          open_timeout: float = 90.0) -> dict:
    """自动检测游戏是否已打开 -> 确认登录前的提示弹窗 -> 点击「登录游戏」-> 等待进入主场景。

    - 已打开：直接登录；已在主场景则跳过点击，返回 already_in_game
    - 未打开：auto_open=True 时自动启动客户端（等待 open_timeout 秒）后继续登录
    - 登录前/登录中出现的公告、设备风险提示等弹窗，先点「确定」关掉再继续
    """
    win, launched, err = _ensure_game_window(cfg, auto_open=auto_open,
                                             open_timeout=open_timeout)
    if win is None:
        _log.warning("login: 未就绪: %s", err.get("error") if err else "未知原因")
        return err
    _log.info("login: 开始（窗口=%s timeout=%.0fs launched=%s）", win, timeout, launched)
    ctx = BotContext(cfg, win)
    # 记录这一轮关掉了哪些弹窗按钮，便于回看为什么慢/点了什么
    dialogs: list[str] = []

    # 0) 已经在主场景（HUD 关键字命中）就不必再点登录
    if _in_main_scene(ctx, cfg):
        _log.info("login: 已在主场景，跳过登录")
        return {"ok": True, "stage": "main_scene", "already_in_game": True,
                "launched": launched, "dialogs": dialogs, "window": str(win)}

    # 1) 先确认掉登录前的提示弹窗，再等登录按钮可用（加载/排队文案消失）
    button = _wait_ready(ctx, cfg, timeout, dialogs)
    if button is None:
        _log.warning("login: 登录画面未就绪（弹窗已关 %d 次）", len(dialogs))
        return {"ok": False, "stage": "wait_ready", "launched": launched,
                "dialogs": dialogs,
                "error": "登录画面未就绪（服务区组仍在加载或未出现「"
                         + "」「".join(LOGIN_BUTTONS) + "」按钮）"}

    # 2) 点击登录按钮
    if not ctx.click_text(button, timeout=5):
        _log.warning("login: 未点击到「%s」按钮", button)
        return {"ok": False, "stage": "click_login", "launched": launched,
                "dialogs": dialogs,
                "error": f"未点击到「{button}」按钮"}
    _log.info("login: 已点击「%s」，等待进入主场景", button)

    # 3) 等待进入主场景（HUD 关键字出现），期间同样先确认弹窗
    entered = _wait_main_scene(ctx, cfg, timeout=timeout, dialogs=dialogs)
    if not entered:
        _log.warning("login: 点击后 %.0fs 内未检测到主场景 HUD", timeout)
        return {"ok": False, "stage": "clicked_login", "launched": launched,
                "dialogs": dialogs,
                "error": "已点击登录但未检测到主场景 HUD，可能需手动选角/排队"}
    _log.info("login: 已进入主场景（弹窗 %d 个）", len(dialogs))
    return {"ok": True, "stage": "main_scene", "launched": launched,
            "dialogs": dialogs, "window": str(win)}


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


def _wait_ready(ctx: BotContext, cfg: dict, timeout: float,
                dialogs: list[str] | None = None) -> str | None:
    """轮询直到登录按钮出现且无加载类文案（判定服务区组已加载完成）。

    返回画面里命中的登录按钮文案（「进入游戏」/「登录游戏」），超时返回 None。
    弹窗会盖住登录按钮，所以等待期间每轮都先尝试确认弹窗。
    """
    end = time.time() + timeout
    while time.time() < end:
        results = _ocr(cfg, ctx.grab())
        texts = [r.text for r in results]
        hits = [b for b in LOGIN_BUTTONS if any(b in t for t in texts)]
        loading = any(any(h in t for h in LOADING_HINTS) for t in texts)
        if hits and not loading:
            return hits[0]
        _confirm_dialog(ctx, results, texts, dialogs)
        time.sleep(1.0)
    return None


def _confirm_dialog(ctx: BotContext, results, texts: list[str],
                    dialogs: list[str] | None = None) -> bool:
    """画面上有提示弹窗的「确定」类按钮就点掉，返回是否点过。

    只在登录流程里调用：此时出现「确定/确认/我知道了」基本就是公告或设备风险提示。
    已进主场景（HUD 命中）时不动手，避免误点游戏内的确认框。
    """
    if any(any(h in t for h in MAIN_SCENE_HINTS) for t in texts):
        return False
    for button in DIALOG_CONFIRM_BUTTONS:
        if not any(button in t for t in texts):
            continue
        if dialogs is not None and dialogs.count(button) >= DIALOG_MAX_CLICKS:
            return False
        hit = BotContext.find_in(results, button)
        if hit is None:
            continue
        _log.info("login: 关闭提示弹窗，点击「%s」(%d,%d)%s", button, hit.cx, hit.cy,
                  f"（第 {dialogs.count(button) + 1} 次）" if dialogs is not None else "")
        ctx.input.click(hit.cx, hit.cy)
        if dialogs is not None:
            dialogs.append(button)
        return True
    return False


def _in_main_scene(ctx: BotContext, cfg: dict) -> bool:
    """单次截图 OCR 判断是否已在主场景。"""
    texts = [r.text for r in _ocr(cfg, ctx.grab())]
    return any(any(h in t for h in MAIN_SCENE_HINTS) for t in texts)


def _wait_main_scene(ctx: BotContext, cfg: dict, timeout: float,
                     dialogs: list[str] | None = None) -> bool:
    """轮询直到出现主场景 HUD 关键字之一；期间弹出的提示框先确认掉。"""
    end = time.time() + timeout
    while time.time() < end:
        results = _ocr(cfg, ctx.grab())
        texts = [r.text for r in results]
        if any(any(h in t for h in MAIN_SCENE_HINTS) for t in texts):
            return True
        _confirm_dialog(ctx, results, texts, dialogs)
        time.sleep(1.5)
    return False
