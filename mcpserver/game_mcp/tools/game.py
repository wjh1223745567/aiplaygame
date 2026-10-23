"""游戏功能 · 启动、登录、回主界面与关闭。

open_game / login / back_to_main / close_game 均返回 JSON 友好的 dict（供 MCP server 直接透出）。
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
# 主场景 HUD 关键字（弱特征：面板里也可能出现，需配合强特征或数量才能确认）
MAIN_SCENE_HINTS = ["商城", "包裹", "背包", "任务", "自动", "地图", "经验"]
# 主场景强特征：只在游戏世界层出现，面板/弹窗会盖住它们。
# 不能用「好友/世界」——它们属于底部聊天栏，商城这类半透明面板打开时仍然可见。
MAIN_SCENE_STRONG = ["展开", "收起", "天墉城", "天塘城"]
# 点进游戏世界的按钮：登录页是「进入游戏」，选角页/二次确认页可能换成别的文案
ENTER_BUTTONS = ["进入游戏", "开始游戏", "进入世界", "进入江湖"]
# 加载/排队阶段的提示文案（出现时说明服务区组尚未就绪）
LOADING_HINTS = ["登录中", "排队", "加载", "连接服务器", "正在连接"]
# 登录前的提示弹窗（公告 / 设备风险提示 / 协议更新）：“确定”类按钮，长的放前面优先命中
DIALOG_CONFIRM_BUTTONS = ["我知道了", "知道了", "确定", "确认"]
# 弹窗正文关键字：按钮文案 OCR 不稳时靠它认定「画面被弹窗挡住」
DIALOG_HINTS = ["安全风险", "切换到正常环境"]
# 同一个按钮最多点几次，防止弹窗关不掉时死磕
DIALOG_MAX_CLICKS = 3
# 正文命中却找不到按钮时，在正文下方这个像素距离处兜底点一下
DIALOG_FALLBACK_DY = 60
# 连续这么多轮关不掉就不再空等，直接返回失败
DIALOG_STUCK_LIMIT = 4
# 一轮登录里同一个进入类按钮最多点几次（点完画面没变通常是没生效，允许重试）
ENTER_MAX_CLICKS = 3
# 点完进入类按钮后的观察时间，避免同一画面被反复点
ENTER_CLICK_WAIT = 2.0
# 返回主界面时要关掉的按钮：弹窗（公告/奖励/确认框）与面板自带关闭键都在此列
# 长的放前面，避免「知道了」被短词抢先；不放「取消」——它可能是面板里的“取消操作”而非关闭
CLOSE_BUTTONS = ["我知道了", "知道了", "关闭", "确定", "确认"]
# 一轮 back_to_main 最多操作几步（点一次或按一次 ESC 算一步）
BACK_MAX_STEPS = 8
# 每步操作后的观察时间，等界面动画 / 网络回包
BACK_STEP_WAIT = 1.0
# 战斗界面特征：命中就不按 ESC，避免误触逃跑/中断战斗
BATTLE_HINTS = ["逃跑", "防御", "自动战斗", "法术"]
# 面板（背包/商城/角色）右上角的关闭键是无文字图标，OCR 抓不到：按截图比例给候选点
# （788x619 的窗口实测商城面板 X 中心在 700,100 附近）
PANEL_CLOSE_RATIOS = [(0.888, 0.162), (0.865, 0.135)]

# 关闭策略：该客户端实测对 Cmd+Q 和 SIGTERM 都不理会，只有 SIGKILL 真正生效，
# 所以每级只留极短等待（给存档一次机会），不再为了「优雅」空等十几秒。
CLOSE_QUIT_WAIT = 2.5   # 正常退出（等同 Cmd+Q）等待
CLOSE_TERM_WAIT = 1.5   # SIGTERM 等待
CLOSE_KILL_WAIT = 2.0   # SIGKILL 后确认进程消失的等待
CLOSE_POLL = 0.2        # 探活轮询间隔


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


def close_game(cfg: dict, timeout: float = CLOSE_QUIT_WAIT, force: bool = False) -> dict:
    """关闭问道客户端并等待进程退出。

    分三级升级（每级都等进程真的退完再决定要不要升级）：
      1) 正常退出（等同 Cmd+Q，能存档/正常下线），等 `timeout` 秒（默认 CLOSE_QUIT_WAIT）；
      2) SIGTERM，等 CLOSE_TERM_WAIT 秒；
      3) SIGKILL 强杀，等 CLOSE_KILL_WAIT 秒（来不及存档）。
    实测该客户端对前两级都不理会、只有 SIGKILL 生效，所以每级只保留极短等待：
    给存档留一次机会，但不再为了「优雅」空等十几秒。
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
    _log.info("close_game: 目标进程 %s force=%s timeout=%.1fs", targets, force, timeout)

    if force:
        _log.warning("close_game: force=True，直接 SIGKILL %s（客户端来不及存档）", targets)
        for pid in targets:
            request_quit(pid, stage="kill")
        still = _wait_exit(targets, CLOSE_KILL_WAIT)
        return _close_result(targets, still, killed=targets, forced=True)

    for pid in targets:
        request_quit(pid)
    remaining = _wait_exit(targets, timeout)
    if remaining:
        _log.warning("close_game: %s 未在 %.1fs 内退出，升级 SIGTERM", remaining, timeout)
        for pid in remaining:
            request_quit(pid, stage="term")
        remaining = _wait_exit(remaining, CLOSE_TERM_WAIT)
    if not remaining:
        return _close_result(targets, [], killed=[], forced=False)

    _log.warning("close_game: %s 仍未退出，升级 SIGKILL", remaining)
    for pid in remaining:
        request_quit(pid, stage="kill")
    still = _wait_exit(remaining, CLOSE_KILL_WAIT)
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


def _wait_exit(pids: list[int], timeout: float, poll: float = CLOSE_POLL) -> list[int]:
    """轮询等待进程退出，返回超时后仍存活的 PID（全退光则为空）。"""
    end = time.time() + timeout
    while True:
        if not alive_pids(pids):
            return []
        if time.time() >= end:
            return alive_pids(pids)
        time.sleep(poll)


def login(cfg: dict, timeout: float = 120.0, auto_open: bool = True,
          open_timeout: float = 90.0) -> dict:
    """一步到位把游戏推进到主场景：启动 -> 关弹窗 -> 点「进入游戏」-> 等 HUD。

    - 已打开：直接登录；已在主场景则跳过点击，返回 already_in_game
    - 未打开：auto_open=True 时自动启动客户端（等待 open_timeout 秒）后继续登录
    - 登录前/登录中出现的公告、设备风险提示等弹窗，先点「确定」关掉再继续
    - 点完登录按钮后可能停在选角页/二次确认页，继续点进入类按钮直到 HUD 出现
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

    # 3) 一路推进到主场景：关弹窗 -> 点残留的进入类按钮 -> 判 HUD
    #    第一次点击已经发生，记进 clicks 里，避免对同一按钮反复空点
    clicks: dict[str, int] = {button: 1}
    entered = _advance_to_main_scene(ctx, cfg, timeout=timeout, dialogs=dialogs,
                                     clicks=clicks)
    if not entered:
        _log.warning("login: 点击后 %.0fs 内未检测到主场景 HUD（进入按钮已点 %s）",
                     timeout, clicks)
        return {"ok": False, "stage": "clicked_login", "launched": launched,
                "dialogs": dialogs, "clicks": clicks,
                "error": "已点击登录但未检测到主场景 HUD，可能需要输入账号密码、选角或排队"}
    _log.info("login: 已进入主场景（弹窗 %d 个，进入按钮 %s）", len(dialogs), clicks)
    return {"ok": True, "stage": "main_scene", "launched": launched,
            "dialogs": dialogs, "clicks": clicks, "window": str(win)}


def back_to_main(cfg: dict, max_steps: int = BACK_MAX_STEPS) -> dict:
    """从任意界面回到游戏主界面：先关弹窗/面板，关不掉就按 ESC 逐级退出。

    - 已在主界面且看不到弹窗 -> already_in_main，不做任何点击
    - 画面有「关闭/确定/确认」类按钮 -> 点掉（公告、奖励、确认框、面板关闭键）
    - 面板没有文字关闭键 -> 点右上角的 X 图标（按截图比例估算）
    - 都不行 -> 按 ESC 返回上一级（窗口不在前台时宁可不发，避免按键落到别的应用）
    同一个按钮最多点 DIALOG_MAX_CLICKS 次；战斗界面不按 ESC（避免误触逃跑）；
    max_steps 步内没回到主界面则报失败，交给人工处理。
    """
    win = _window(cfg)
    if win is None:
        return {"ok": False, "stage": "detect", "error": "未找到游戏窗口，请先 open_game 或手动打开"}
    ctx = BotContext(cfg, win)
    # 点击/按键都是全局事件：窗口不在前台时可能落到别的应用上，先确认能激活
    if not ctx.input.frontmost():
        return {"ok": False, "stage": "not_frontmost", "closed": [], "esc_presses": 0,
                "error": "游戏窗口不在前台且无法自动激活，按键会落到其他应用；请手动切到游戏窗口后重试"}
    closed: list[str] = []
    esc = 0
    tried_points: list[tuple[int, int]] = []
    for step in range(1, max_steps + 1):
        img = ctx.grab()
        results = _ocr(cfg, img)
        texts = [r.text for r in results]
        button = _close_button(texts, closed)
        hit = BotContext.find_in(results, button) if button else None
        if hit is not None:
            _log.info("back_to_main: 关闭「%s」(%d,%d)（第 %d 步）", button, hit.cx, hit.cy, step)
            ctx.input.click(hit.cx, hit.cy)
            closed.append(button)
            time.sleep(BACK_STEP_WAIT)
            continue
        if _main_scene_hit(texts):
            _log.info("back_to_main: 已回到主界面（关弹窗 %s、ESC %d 次）", closed, esc)
            return {"ok": True, "stage": "main_scene",
                    "already_in_main": not closed and esc == 0,
                    "closed": closed, "esc_presses": esc, "steps": step,
                    "window": str(win)}
        if any(any(h in t for h in BATTLE_HINTS) for t in texts):
            _log.warning("back_to_main: 当前在战斗界面，不按 ESC（避免逃跑），放弃")
            return {"ok": False, "stage": "battle", "closed": closed, "esc_presses": esc,
                    "error": "当前在战斗界面，未做任何操作；等战斗结束再调"}
        if any(any(h in t for h in LOADING_HINTS) for t in texts):
            time.sleep(BACK_STEP_WAIT)
            continue
        # 面板（背包/商城/角色）的关闭键是无文字图标：按截图比例点右上角
        point = _panel_close_point(img.shape[1], img.shape[0], tried_points)
        if point is not None:
            _log.info("back_to_main: 点面板右上角关闭 (%d,%d)（第 %d 步）", point[0], point[1], step)
            ctx.input.click(point[0], point[1])
            tried_points.append(point)
            time.sleep(BACK_STEP_WAIT)
            continue
        _log.info("back_to_main: 不在主界面且无可点关闭按钮，按 ESC（第 %d 步）", step)
        if not ctx.input.press_key("esc"):
            return {"ok": False, "stage": "not_frontmost", "closed": closed, "esc_presses": esc,
                    "error": "按键时游戏窗口已不在前台，已停止（避免按键落到其他应用）"}
        esc += 1
        time.sleep(BACK_STEP_WAIT)
    _log.warning("back_to_main: %d 步内未回到主界面（关弹窗 %s、ESC %d 次）", max_steps, closed, esc)
    return {"ok": False, "stage": "max_steps", "closed": closed, "esc_presses": esc,
            "error": f"{max_steps} 步内未回到主界面，可能卡在面板或弹窗，需要人工处理"}


def _close_button(texts: list[str], closed: list[str]) -> str | None:
    """画面上可点的关闭类按钮；已点满次数的跳过。"""
    for button in CLOSE_BUTTONS:
        if closed.count(button) >= DIALOG_MAX_CLICKS:
            continue
        if any(button in t for t in texts):
            return button
    return None


def _panel_close_point(width: int, height: int,
                       tried: list[tuple[int, int]]) -> tuple[int, int] | None:
    """面板右上角 X 的候选位置（截图像素坐标），已点过的跳过。"""
    for rx, ry in PANEL_CLOSE_RATIOS:
        point = (int(width * rx), int(height * ry))
        if point not in tried:
            return point
    return None


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
    弹窗会盖住登录按钮（按钮文字仍可能被 OCR 读到），所以**有弹窗时一律先关掉**，
    本轮不判就绪——否则会对着遮罩层点「进入游戏」，点了没反应还以为已点过。
    """
    end = time.time() + timeout
    stuck = 0
    while time.time() < end:
        results = _ocr(cfg, ctx.grab())
        texts = [r.text for r in results]
        if _has_dialog(texts):
            if _confirm_dialog(ctx, results, texts, dialogs):
                stuck = 0
            else:
                stuck += 1
                if stuck >= DIALOG_STUCK_LIMIT:
                    _log.warning("login: 提示弹窗连续 %d 轮关不掉，放弃", stuck)
                    return None
            time.sleep(0.8)
            continue
        hits = [b for b in LOGIN_BUTTONS if any(b in t for t in texts)]
        loading = any(any(h in t for h in LOADING_HINTS) for t in texts)
        if hits and not loading:
            return hits[0]
        time.sleep(1.0)
    return None


def _has_dialog(texts: list[str]) -> bool:
    """画面里是否有挡路的提示弹窗：确认按钮或已知正文任一命中。"""
    if any(any(b in t for t in texts) for b in DIALOG_CONFIRM_BUTTONS):
        return True
    return any(any(h in t for t in texts) for h in DIALOG_HINTS)


def _confirm_dialog(ctx: BotContext, results, texts: list[str],
                    dialogs: list[str] | None = None) -> bool:
    """画面上有提示弹窗就点掉，返回是否点过。

    只在登录流程里调用：此时出现「确定/确认/我知道了」基本就是公告或设备风险提示。
    已进主场景（HUD 命中）时不动手，避免误点游戏内的确认框。
    某个按钮点到上限后换下一个候选（而不是整体放弃）；连按钮文案都认不出时，
    按正文位置向下偏移兜底点一次，应对 OCR 把「确认」识别成别的字。
    """
    if any(any(h in t for h in MAIN_SCENE_HINTS) for t in texts):
        return False
    for button in DIALOG_CONFIRM_BUTTONS:
        if not any(button in t for t in texts):
            continue
        if dialogs is not None and dialogs.count(button) >= DIALOG_MAX_CLICKS:
            continue
        hit = BotContext.find_in(results, button)
        if hit is None:
            continue
        _log.info("login: 关闭提示弹窗，点击「%s」(%d,%d)%s", button, hit.cx, hit.cy,
                  f"（第 {dialogs.count(button) + 1} 次）" if dialogs is not None else "")
        ctx.input.click(hit.cx, hit.cy)
        if dialogs is not None:
            dialogs.append(button)
        time.sleep(0.6)
        return True

    # 兜底：正文命中但按钮认不出，在正文块下方点一次（弹窗按钮通常居中排在文字下面）
    if dialogs is not None and dialogs.count("fallback") >= DIALOG_MAX_CLICKS:
        return False
    body = None
    for hint in DIALOG_HINTS:
        hit = BotContext.find_in(results, hint)
        if hit is not None and (body is None or hit.cy > body.cy):
            body = hit  # 取最靠下的一句，避免点到标题行
    if body is None:
        return False
    x, y = body.cx, body.cy + DIALOG_FALLBACK_DY
    _log.info("login: 弹窗按钮文案未识别，按正文兜底点击 (%d,%d)", x, y)
    ctx.input.click(x, y)
    if dialogs is not None:
        dialogs.append("fallback")
    time.sleep(0.6)
    return True


def _main_scene_hit(texts: list[str]) -> bool:
    """这批 OCR 文本是否属于游戏主界面。

    只看「商城/任务」会误判：商城面板里同样有「商城」，还可能顺带出现「地图」。
    所以要求命中背包栏/地图名这类强特征，或者三个以上弱特征同时出现
    （商城面板实测只命中「商城」「地图」两个）。
    """
    strong = any(any(h in t for h in MAIN_SCENE_STRONG) for t in texts)
    weak = sum(1 for h in MAIN_SCENE_HINTS if any(h in t for t in texts))
    return strong or weak >= 3


def _in_main_scene(ctx: BotContext, cfg: dict) -> bool:
    """单次截图 OCR 判断是否已在主场景。"""
    return _main_scene_hit([r.text for r in _ocr(cfg, ctx.grab())])


def _advance_to_main_scene(ctx: BotContext, cfg: dict, timeout: float,
                           dialogs: list[str] | None = None,
                           clicks: dict[str, int] | None = None) -> bool:
    """从登录页一路推进到主场景：关弹窗 -> 点进入类按钮 -> 判 HUD。

    点完登录按钮不等于进了游戏：有的号停在选角页，有的是「开始游戏」二次确认，
    所以画面上还能认出的进入类按钮继续点掉，直到 HUD 出现或超时。
    加载类文案出现时不下手；同一按钮最多点 ENTER_MAX_CLICKS 次，避免空点。
    """
    clicks = {} if clicks is None else clicks
    end = time.time() + timeout
    while time.time() < end:
        results = _ocr(cfg, ctx.grab())
        texts = [r.text for r in results]
        if _main_scene_hit(texts):
            return True
        if _has_dialog(texts):
            _confirm_dialog(ctx, results, texts, dialogs)
            time.sleep(0.8)
            continue
        loading = any(any(h in t for h in LOADING_HINTS) for t in texts)
        button = None if loading else _enter_button(texts, clicks)
        hit = BotContext.find_in(results, button) if button else None
        if hit is not None:
            _log.info("login: 点「%s」推进主场景 (%d,%d)（第 %d 次）",
                      button, hit.cx, hit.cy, clicks.get(button, 0) + 1)
            ctx.input.click(hit.cx, hit.cy)
            clicks[button] = clicks.get(button, 0) + 1
            time.sleep(ENTER_CLICK_WAIT)
            continue
        time.sleep(1.2)
    return False


def _enter_button(texts: list[str], clicks: dict[str, int]) -> str | None:
    """画面上还能点的进入类按钮；已点满次数的不再返回。"""
    for button in ENTER_BUTTONS:
        if clicks.get(button, 0) >= ENTER_MAX_CLICKS:
            continue
        if any(button in t for t in texts):
            return button
    return None
