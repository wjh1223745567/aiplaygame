"""问道游戏自动化 · MCP Server 入口（MCPServer）。

通过 MCP 暴露两类工具：
  · 基础能力：ping / list_windows / capture / ocr / layout / detect / click_point /
             click_text / press_key / advise
  · 游戏功能：open_game / close_game / game_status / login / back_to_main / daily_task / market_scan（见 tools/）

内部统一复用 BotContext（core/engine.py）；窗口靠 config.window.title_keywords 定位。
两种传输（由 AUTOGAME_MCP_TRANSPORT 选择）：
  · stdio（默认）：`python -m game_mcp.server`，由 Java client 作为子进程拉起；
  · streamable-http：常驻监听，供容器里的 host 通过 AUTOGAME_MCP_URL 连接。
    因为要操作 macOS 本机窗口，这个进程只能跑在宿主机，不能放进 Linux 容器。
    同一个 HTTP 服务上还挂了一个 GET /frame.jpg：当前画面快照，给人看（客户端「卡住时
    看一眼」），不进工具清单、不发给模型。
"""
from __future__ import annotations

import functools
import inspect
import os
import platform
import signal
import threading
import time
from typing import List, Optional

from mcp.server.mcpserver import MCPServer

from . import config as cfgmod
from .core import capture as cap
from .core import log as runlog
from .core import input as user_input
from .core.engine import BotContext
from .core.human_guard import get_guard
from .core.window import find_game_window, list_windows as _list_windows
from .vision.screen import detect_layers
from .tools import game as game_tools
from .tools import daily as daily_tools

app = MCPServer("wendao-game", version="0.1.0")

log = runlog.get_logger("server")


class WindowNotFound(RuntimeError):
    """当前没有可用的游戏窗口（未启动 / 最小化 / 在其他桌面）。

    属于预期状态而非程序缺陷：`_traced` 统一把它转成 `ok=false`，
    省掉每个工具各写一遍 `try/except RuntimeError` 的样板。
    """


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


# 豁免名单：不查暂停、永远立即返回。模型看到 paused 就知道该等，而不是重试狂点。
_PAUSE_EXEMPT = frozenset({"ping", "list_windows", "game_status"})

# 写操作工具：它们共用唯一的一套鼠标键盘和唯一的一个游戏窗口，必须串行。
# 并发是真实存在的：host 与 Swing 客户端是两条独立 MCP 连接，一次单步推进（daily_task）
# 期间另一路调 click_point，两次 program_action 会交错，鼠标插值互相
# 打断（_move_to_raw 会读到对方的中途坐标），表现出来就是「点歪」。
_WRITE_TOOLS = frozenset({
    "click_point", "click_text", "press_key",
    "open_game", "close_game", "login", "back_to_main", "daily_task", "market_scan",
})

# 可重入：工具实现内部若再调受管控的工具，不会把自己锁死。
_WRITE_LOCK = threading.RLock()
# 当前持锁的工具名（栈）：只用于 busy 提示与日志，不参与判定。
_running: List[str] = []


def _pause_block(name: str):
    """工具入口统一查人工接管暂停。返回 None 表示可以继续，否则返回提示 dict。

    用 max_block_sec 封顶（默认 30s）：单步推进（daily_task）本来就要等一轮模型，
    等 10s 无妨；但 ocr / click_point 这类短工具若阻塞太久，客户端会以为是卡死。
    """
    if name in _PAUSE_EXEMPT:
        return None
    try:
        guard = get_guard(cfgmod.load_config().get("human_guard", {}))
    except Exception:  # noqa: BLE001 配置读不到也不能阻住工具
        guard = get_guard()
    if guard.wait_if_paused(guard.max_block_sec):
        return None
    return {"ok": False, "paused": True, "resume_in_sec": round(guard.resume_in(), 1),
            "error": f"检测到人工操作，自动化已暂停；鼠标静止 {guard.idle_resume_sec:.0f}s 后自动恢复"}


def tool(fn):
    """注册 MCP 工具，并记录参数、耗时、成败与异常。

    做法：先照原函数原样注册（schema 完全由原签名与原返回注解推导，不受影响），
    再把 Tool 的执行入口 `fn` 换成带日志的包装——这样既能统一埋点，
    也不会因为包装函数改变 structured output 的推导结果。
    返回值也用包装函数：直接调模块函数（调试/单测）与 MCP 调用走同一条路径，
    否则 `WindowNotFound` 这类统一处理只在 MCP 入口生效。
    """
    app.tool()(fn)
    wrapped = _traced(fn)
    registered = app._tool_manager.get_tool(fn.__name__)
    if registered is not None:
        registered.fn = wrapped
    return wrapped


def _returns_dict(sig: inspect.Signature) -> bool:
    """工具的返回注解是否为 dict。

    本模块用了 `from __future__ import annotations`，注解是字符串，不能直接和 dict 比。
    没标注的按 dict 处理（绝大多数工具都是 dict）。
    """
    ann = sig.return_annotation
    if ann is inspect.Signature.empty:
        return True
    name = ann if isinstance(ann, str) else getattr(ann, "__name__", "")
    return name in ("dict", "Dict") or name.startswith(("dict[", "Dict["))


def _traced(fn):
    """给工具函数套一层日志：一次调用一条日志，异常先留痕再转成 ok=false。

    入口统一做三件事：
      1. 检查人工接管暂停：短工具最多阻塞 max_block_sec，超时直接返回 paused=true，
         避免客户端表现为“卡死”；
      2. 写操作串行（见 _WRITE_TOOLS）：抢不到锁立即返回 busy=true，不排队；
      3. 异常兜底：返回 dict 的工具转成 ok=false，模型能看懂下一步该干什么。
    """
    sig = inspect.signature(fn)
    name = fn.__name__

    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            shown = runlog.brief(dict(sig.bind(*args, **kwargs).arguments), 240)
        except TypeError:  # 参数绑定失败也要留痕，再让原函数自己报错
            shown = runlog.brief({"args": args, "kwargs": kwargs}, 240)
        started = time.perf_counter()
        blocked = _pause_block(name)
        if blocked is not None:
            log.warning("工具 %s 因人工操作暂停而跳过 %dms 参数=%s",
                        name, _ms(started), shown)
            return blocked
        holding = name in _WRITE_TOOLS
        if holding:
            # 不排队等：长任务可能还要跑几分钟，短工具排到天荒地老，模型只能以为卡死。
            # 直接回 busy=true，让调用方知道「现在有别的操作在占着画面」而不是「点击无效」。
            if not _WRITE_LOCK.acquire(blocking=False):
                busy = _running[-1] if _running else "另一个操作类工具"
                log.warning("工具 %s 被拒：%s 正在执行 %dms 参数=%s",
                            name, busy, _ms(started), shown)
                return {"ok": False, "busy": True,
                        "error": f"「{busy}」正在执行，画面与输入都由它占用；等它结束再调 {name}"}
            _running.append(name)
        try:
            result = fn(*args, **kwargs)
        except WindowNotFound as e:
            # 没有可用窗口是可预期状态：统一转 ok=false，工具自己不必再拦一遍
            log.warning("工具 %s 无可用窗口 %dms 参数=%s: %s",
                        name, _ms(started), shown, e)
            return _err(str(e))
        except Exception as e:  # noqa: BLE001
            log.error("工具 %s 异常 %dms 参数=%s: %s",
                      name, _ms(started), shown, e, exc_info=True)
            # 返回 dict 的工具统一转成 ok=false：模型拿到结构化字段比拿到协议层异常
            # 更好判断下一步。返回 list/str 的工具塞不进 dict，只能原样抛（否则签名对不上）。
            if _returns_dict(sig):
                return _err(f"{name} 异常: {type(e).__name__}: {e}")
            raise
        finally:
            if holding:
                if _running:
                    _running.pop()
                _WRITE_LOCK.release()
        if isinstance(result, dict) and result.get("ok") is False:
            log.warning("工具 %s 失败 %dms 参数=%s 结果=%s",
                        name, _ms(started), shown, runlog.brief(result, 300))
        else:
            log.info("工具 %s 完成 %dms 参数=%s", name, _ms(started), shown)
            log.debug("工具 %s 结果=%s", name, runlog.brief(result))
        return result

    return wrapper


# ---------- 内部辅助 ----------
def _find_win(cfg: dict):
    wc = cfg.get("window", {})
    return find_game_window(wc.get("title_keywords", []), wc.get("exclude_keywords"))


def _make_ctx(dry_run: bool = False) -> BotContext:
    """按当前配置与前台游戏窗口构造 BotContext；无窗口时抛 WindowNotFound。"""
    cfg = cfgmod.load_config()
    user_input.set_dry_run(dry_run)
    win = _find_win(cfg)
    if win is None:
        log.warning("未找到游戏窗口（关键词=%s），请先 open_game 或手动打开",
                    cfg.get("window", {}).get("title_keywords"))
        raise WindowNotFound("未找到游戏窗口（请先 open_game 或手动打开游戏）")
    log.debug("命中游戏窗口 %s dry_run=%s", win, dry_run)
    return BotContext(cfg, win)


def _ok(**kw) -> dict:
    return {"ok": True, **kw}


def _err(msg: str) -> dict:
    return {"ok": False, "error": msg}


# ---------- 基础能力 ----------
@tool
def ping() -> str:
    """连通性自检：返回字符串 pong。只读、无副作用，排查工具调不通时先用它。"""
    return "pong"


@tool
def list_windows() -> List[dict]:
    """需要核对窗口标题/尺寸、确认游戏窗口是否最小化时用：枚举当前桌面上可见的窗口。

    每项含 window_id/owner/title/x/y/width/height/scale/pid。只读无副作用；
    最小化或位于其他桌面的窗口不在结果里（这类窗口也无法截图）。
    """
    return [{
        "window_id": w.window_id, "owner": w.owner, "title": w.title,
        "x": w.x, "y": w.y, "width": w.width, "height": w.height,
        "scale": w.scale, "pid": w.pid,
    } for w in _list_windows()]


@tool
def capture(tag: str = "capture") -> dict:
    """需要“看一眼画面”（留档、给人看）时用：截取游戏窗口并存到 debug/，返回 path/width/height/scale。

    只读无副作用；窗口没打开时返回 ok=false。只想知道画面里有哪些文字用 ocr 更快。
    """
    ctx = _make_ctx()
    img = ctx.grab()
    path = cap.debug_save(img, tag, ctx.debug_dir)
    return _ok(path=path, width=img.shape[1], height=img.shape[0], scale=ctx.win.scale)


@tool
def ocr(keyword: Optional[str] = None, region: Optional[List[int]] = None) -> dict:
    """需要知道画面里有哪些文字、或拿文字坐标去点击时用：返回 count 与 texts[{text,confidence,box,cx,cy}]。

    keyword 非空时只保留包含该词的文本（比较时忽略空格，“师门任务”能命中“师门 任务”）。
    region 传 [x1,y1,x2,y2] 时只识别该区域（弹窗/面板内部），坐标仍回换算到整图。
    只读无副作用；cx/cy 可直接喂给 click_point，点击前不确定目标在哪就先 ocr。
    """
    ctx = _make_ctx()
    img = ctx.grab()
    results = ctx.ocr(img, region=region)
    if keyword:
        results = [r for r in results if keyword in r.text.replace(" ", "")]
    return _ok(count=len(results), texts=[
        {"text": r.text, "confidence": round(r.confidence, 3),
         "box": list(r.box), "cx": round(r.cx), "cy": round(r.cy)}
        for r in results
    ])


@tool
def layout() -> dict:
    """需要查看当前画面被分成哪些区域（按钮/任务/信息/弹窗）时用：返回 count 与
    regions[{kind,source,text,box,cx,cy,confidence}]。

    kind 取 button/icon/task/dialog/entity/info/label，source 取 template/yolo/general/ocr；
    只有 kind 在 vision.clickable_kinds（现为 button / icon）里的区域会被当作可点击项，
    其余（含 entity 游戏实体）只是供判断的只读信息。
    只读无副作用；模板与 YOLO 都未就绪时等价于 OCR 按行聚合的文本（source=ocr）。
    """
    ctx = _make_ctx()
    regions = ctx.analyze()
    return _ok(count=len(regions), regions=[
        {**r.to_dict(), "confidence": round(r.confidence, 3)} for r in regions
    ])


@tool
def detect(classes: Optional[List[str]] = None, layer: str = "all") -> dict:
    """需要按图案找 UI 控件或游戏实体时用：返回 count、model_classes 与
    detections[{label,kind,layer,confidence,box,cx,cy}]。

    layer 取 all（默认：先用 UITag 分类控件，再用通用模型找实体，去重合并、UI 优先）、
    ui（仅 UITag）、general（仅通用模型）。
    classes 非空时只保留匹配的检测：可写模型类别名（Button / person）也可写内部 kind
    （button / icon / entity…），不区分大小写；为空时 ui 层用 ui_classes、general 层用
    general.classes。kind 决定该区域是否可点（见 vision.clickable_kinds，现为 button / icon），
    layer=general 的结果一律是只读锚点。本层只做区域分类，不区分按钮语义（保存还是取消
    由区内 OCR 文字给出），也不输出坐标给模型选；复用会话上已建好的两个检测器（权重懒加载
    且实例级缓存），不会每次调用都重新读盘。权重缺失时返回 ok=false。只读无副作用。
    """
    layer = (layer or "all").strip().lower()
    if layer not in ("all", "ui", "general"):
        return _err("layer 只能是 all / ui / general")
    ctx = _make_ctx()
    layers = ("ui", "general") if layer == "all" else (layer,)
    dets = detect_layers(ctx, ctx.grab(), layers=layers, classes=classes)
    errs = [f"{name}: {d._load_error}"
            for name, d in (("ui", ctx.yolo), ("general", ctx.general))
            if name in layers and d._load_error]
    if not dets and errs:
        return _err("YOLO 加载失败 " + "；".join(errs))
    return _ok(count=len(dets),
               model_classes={"ui": ctx.yolo.class_names,
                              "general": ctx.general.class_names},
               detections=[
                   {"label": d.label, "kind": d.kind, "layer": d.layer,
                    "confidence": round(d.confidence, 3),
                    "box": list(d.box), "cx": round(d.cx), "cy": round(d.cy)}
                   for d in dets
               ],
               **({"warnings": errs} if errs else {}))


@tool
def click_point(x: float, y: float, dry_run: bool = False) -> dict:
    """已知目标的像素坐标时用它精确点击：坐标系与 ocr 的 box、capture 的截图一致（不是屏幕坐标）。

    dry_run=True 只打印不点击。窗口不在前台时不点击并返回 ok=false（避免点到其他应用）。
    真实操作：只在用户明确要求，或看屏确认位置后使用。
    """
    ctx = _make_ctx(dry_run=dry_run)
    if not ctx.click_point(x, y):
        return _err(f"点击 ({x},{y}) 未发送：游戏窗口不在前台或自动化已暂停，详见 mcpserver.log")
    return _ok(clicked=[x, y], dry_run=dry_run)


@tool
def click_text(text: str, dry_run: bool = False) -> dict:
    """已知目标文字、且画面里该文字唯一时用：找包含 text 的文本并点它的中心。

    默认最多等 10 秒，找不到返回 ok=false 且不点击；有多个同名文本时改用 ocr 拿 cx/cy 再 click_point。
    dry_run=True 只打印不点击。窗口不在前台时同样不点击（返回 ok=false）。
    真实操作：只点用户明确指定的目标。
    """
    ctx = _make_ctx(dry_run=dry_run)
    hit = ctx.click_text(text)
    if not hit:
        return _err(f"未点击「{text}」：画面上没找到该文本，或游戏窗口不在前台无法发送点击")
    return _ok(clicked_text=text, dry_run=dry_run)


@tool
def press_key(key: str, dry_run: bool = False) -> dict:
    """需要按键盘（Esc 关界面、回车确认、方向键）时用：按一次 esc / enter / space / tab /
    backspace / delete / 方向键 / f10-f12，或任意单字符。

    dry_run=True 只打印不按键。窗口不在前台时不发送并返回 ok=false（避免按键落到别的应用上）。
    真实操作：用户明确要求按键时才按。
    """
    ctx = _make_ctx(dry_run=dry_run)
    if not ctx.input.press_key(key):
        return _err(f"按键 {key} 未发送：游戏窗口不在前台，避免落到其他应用")
    return _ok(key=key, dry_run=dry_run)


@tool
def advise(goal: str = "", dry_run: bool = True) -> dict:
    """只想让视觉模型给一步建议、不执行任何动作时用：返回 scene/reason/done/actions/clickable/context。

    goal 是本次目标（为空按“推进当前任务”），必须来自用户原话。要一步一步真正推动游戏用
    daily_task（它也是单步，可反复调用）。
    clickable 是带 id 的可点项（现阶段只有按钮类区域），context 是只读画面信息，模型只能从 clickable 选 id。
    dry_run 对 advise 无实际影响（它从不点击）。
    """
    ctx = _make_ctx(dry_run=dry_run)
    if not ctx.advisor.api_key:
        return _err("未配置 llm.api_key")
    from .vision.llm import LlmError
    img = ctx.grab()
    results = ctx.ocr(img)
    size = (img.shape[1], img.shape[0])
    try:
        plan = ctx.advisor.advise(ctx.analyze(img, results), goal or "推进当前任务",
                                 size=size)
    except LlmError as e:
        return _err(f"大模型调用失败: {e}")
    return _ok(scene=plan.get("scene"), reason=plan.get("reason"),
               done=plan.get("done"), actions=plan.get("actions", []),
               clickable=plan.get("clickable", []), context=plan.get("context", []),
               dry_run=dry_run)


# ---------- 游戏功能（见 tools/） ----------
@tool
def open_game() -> dict:
    """用户只说“打开游戏”“把游戏开起来”时用：启动问道客户端，已在运行则只激活窗口，
    两种情况都等到窗口出现才返回 ok=true。

    真实操作（拉起进程/切前台），不点登录按钮；用户要登录、进到游戏里请用 login。
    """
    try:
        return game_tools.open_game(cfgmod.load_config())
    except Exception as e:  # noqa: BLE001
        return _err(f"打开游戏失败: {e}")


@tool
def close_game(force: bool = False, timeout: float = 2.5) -> dict:
    """用户说“关闭游戏”“退出游戏”时用：先请应用正常退出（可存档），timeout 秒没退就 SIGTERM，
    再 1.5 秒仍在则 SIGKILL。

    实测该客户端忽略 Cmd+Q 和 SIGTERM，所以默认 2.5 秒就会走到强杀。
    force=True 跳过前两级直接杀（未保存进度会丢），除非用户明确要求不然不用。
    返回 closed/killed/forced；本来就没在跑时返回 already_closed=true。
    """
    try:
        return game_tools.close_game(cfgmod.load_config(), timeout=timeout, force=force)
    except Exception as e:  # noqa: BLE001
        return _err(f"关闭游戏失败: {e}")


@tool
def game_status() -> dict:
    """用户问“游戏开了吗”“窗口在不在”时用：只读返回 running / window_visible / pids，
    不会启动或点击任何东西。

    拿到结果直接用中文回答“开着/没开”，不要顺手做别的，也不要贴字段名。
    若检测到人工接管（鼠标被人移动/点击），额外返回 paused=true 与 resume_in_sec（剩余秒数），
    此时不要重试点击/按键类工具，静待用户停手后自动恢复。
    """
    try:
        cfg = cfgmod.load_config()
        guard = get_guard(cfg.get("human_guard", {}))
        paused = guard.paused
        return _ok(**game_tools.is_game_running(cfg), paused=paused,
                   resume_in_sec=round(guard.resume_in(), 1) if paused else 0.0)
    except Exception as e:  # noqa: BLE001
        return _err(f"检测失败: {e}")


@tool
def login(timeout: float = 120.0, auto_open: bool = True) -> dict:
    """用户说“登录”“登录游戏”“进入游戏”“进游戏”时用：一步完成登录并进入游戏主页面。

    流程：未启动先启动 -> 关掉公告/风险提示弹窗 -> 点「进入游戏」-> 处理选角页/二次确认页的
    进入类按钮 -> 主场景 HUD 出现才返回 ok=true（实测约 10~20 秒）。
    不要拆成 click_text + game_status（那样点完就收尾，无法确认真的进了主页面）。
    已在主场景时立即返回 already_in_game=true；auto_open=false 则只处理已经打开的游戏。
    """
    try:
        return game_tools.login(cfgmod.load_config(), timeout=timeout, auto_open=auto_open)
    except Exception as e:  # noqa: BLE001
        return _err(f"登录失败: {e}")


@tool
def back_to_main(max_steps: int = 8) -> dict:
    """用户说“回到主界面”“返回主界面”“关掉弹窗”“退出来”时用：关掉弹窗/面板并回到游戏主界面。

    顺序：先点「关闭/确定/确认/我知道了」等文字按钮 -> 面板没有文字关闭键就点右上角 X
    -> 都不行再按 ESC 逐级退出；战斗界面不按 ESC。
    返回 ok / already_in_main / closed / esc_presses / stage；已在主界面且无弹窗时零操作直接返回。
    窗口不在前台时返回 stage=not_frontmost，不做任何输入。max_steps 为最大操作步数（默认 8）。
    """
    try:
        return game_tools.back_to_main(cfgmod.load_config(), max_steps=max_steps)
    except Exception as e:  # noqa: BLE001
        return _err(f"返回主界面失败: {e}")


@tool
def daily_task(goal: str = "", max_steps: int = 1, dry_run: bool = False) -> dict:
    """用户说“做师门任务”“做任务”时用：推进一步师门任务（单步），把过程原样返回。

    一次调用只走一步：识别当前画面 -> 视觉模型从可点项里选 -> 用我方坐标点击/按键。
    返回字段：scene（当前界面）/ reason（为什么这么选）/ chosen（选了什么，带回填文字）/
    executed（实际发出几个动作）/ done（是否已完成）/ clickable、context（本帧可点项与只读信息）/
    n_clickable（本帧可点候选数，参数被截断时看它）/ note（没执行时的原因）。
    循环交给调用方：没 done 且 executed>0 就接着调（goal 照旧，参数重复不算卡住）；
    executed=0 先看 note（可点为空/窗口不在前台/被人工接管），连续两三次仍无动作就停下汇报；
    done=true 收尾。最大步数由调用方的轮数上限控制，本工具默认只走一步（max_steps>1 仅调试用）。
    dry_run=True 只看不动。属操作类长耗时（单步约 3~10s），调用前先确认游戏已登录进主界面。
    """
    ctx = _make_ctx(dry_run=dry_run)
    return daily_tools.daily_task(ctx, goal=goal, max_steps=max_steps)


@tool
def market_scan(goal: str = "", max_steps: int = 1, dry_run: bool = False) -> dict:
    """用户说“扫货”“去集市买东西”时用：推进一步集市扫货（单步），返回同样的过程字段。

    goal 可传筛选条件（如“只买低于 1 万的宝石”）；每次只看一帧、只做一组动作，
    done=true 表示买完或无合适物品。需要游戏已停在集市/商铺界面，否则大概率空转（executed=0）。
    max_steps 默认 1（>1 仅调试用）；dry_run=True 只看不动。
    """
    ctx = _make_ctx(dry_run=dry_run)
    return daily_tools.market_scan(ctx, goal=goal, max_steps=max_steps)


def _install_quit_handler() -> None:
    """host 关闭时通常发 SIGTERM，默认处理会立刻终止进程、finally 跑不到，这里先留痕再退出。"""
    def _on_term(signum, _frame):
        log.info("MCP server 收到 %s，正在退出 pid=%d",
                 signal.Signals(signum).name, os.getpid())
        log.info("MCP server 结束 pid=%d", os.getpid())
        signal.signal(signum, signal.SIG_DFL)
        os.kill(os.getpid(), signum)  # 还原默认处理，保留真实退出码

    try:
        signal.signal(signal.SIGTERM, _on_term)
    except (ValueError, OSError) as e:  # 非主线程或不支持信号
        log.debug("注册 SIGTERM 处理失败: %s", e)


# 传输方式：stdio（默认，host 以子进程拉起）或 streamable-http（容器化部署时常驻）
TRANSPORT = os.getenv("AUTOGAME_MCP_TRANSPORT", "stdio").strip().lower()
HTTP_HOST = os.getenv("AUTOGAME_MCP_HOST", "0.0.0.0").strip()
HTTP_PORT = int(os.getenv("AUTOGAME_MCP_PORT", "18090"))
HTTP_PATH = os.getenv("AUTOGAME_MCP_PATH", "/mcp").strip()
# 抓帧端点挂在同一个 HTTP 服务上（与 MCP 端点同级，host 由 AUTOGAME_MCP_URL 推导出地址）
FRAME_PATH = "/frame.jpg"
# SDK 默认只放行 localhost 的 Host 头，容器经 host.docker.internal 访问会被 421 拒绝，
# 所以这里显式列出允许的 Host（逗号分隔，形如 host:port，port 可写 *）。
HTTP_ALLOWED_HOSTS = [h.strip() for h in os.getenv(
    "AUTOGAME_MCP_ALLOWED_HOSTS",
    "127.0.0.1:*,localhost:*,[::1]:*,host.docker.internal:*").split(",") if h.strip()]


# ---------- 抓帧端点：给人看，不给模型看 ----------
_FRAME_QUALITY = 80


def _grab_jpeg() -> bytes:
    """抓一帧并编码成 JPEG；由 frame_jpg 丢进 worker 线程跑（screencapture 是阻塞的）。"""
    import cv2

    img = _make_ctx().grab()
    ok, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), _FRAME_QUALITY])
    if not ok:
        raise RuntimeError("JPEG 编码失败")
    return buf.tobytes()


@app.custom_route(FRAME_PATH, methods=["GET"])
async def frame_jpg(request):
    """当前游戏画面快照（JPEG），客户端「等太久/疑似卡住」时看一眼用的。

    刻意不注册成 MCP 工具：它不参与决策，也不该出现在模型看到的工具清单里——
    约定一直是只把识别清单发给模型，不发截图。这里同理，是给人看的调试窗口。

    走独立线程、不抢 _WRITE_LOCK，所以单步推进正卡在大模型调用上时照样出图；
    也不落盘（debug/ 只在失败样本时才写），每帧都是一次性字节流。
    """
    from starlette.concurrency import run_in_threadpool
    from starlette.responses import Response

    started = time.perf_counter()
    try:
        jpeg = await run_in_threadpool(_grab_jpeg)
    except WindowNotFound as e:
        return Response(str(e), status_code=503, media_type="text/plain; charset=utf-8")
    except Exception as e:  # noqa: BLE001 出图失败不该把服务带崩
        log.warning("抓帧失败 %dms: %s", _ms(started), e)
        return Response(f"抓帧失败: {type(e).__name__}: {e}", status_code=500,
                        media_type="text/plain; charset=utf-8")
    log.debug("抓帧 %d 字节 %dms（客户端 %s）", len(jpeg), _ms(started),
              getattr(request.client, "host", "?"))
    return Response(jpeg, media_type="image/jpeg", headers={"Cache-Control": "no-store"})


def _run_streamable_http() -> None:
    """常驻 HTTP：host 跑在容器里时经 host.docker.internal 连进来。"""
    from mcp.server.transport_security import TransportSecuritySettings

    log.info("MCP server 监听 http://%s:%d%s allowed_hosts=%s（抓帧 http://%s:%d%s）",
             HTTP_HOST, HTTP_PORT, HTTP_PATH, HTTP_ALLOWED_HOSTS,
             HTTP_HOST, HTTP_PORT, FRAME_PATH)
    if HTTP_HOST in ("0.0.0.0", "::"):
        log.warning("MCP server 绑定 %s，同网段可直连；如需只给本机用，设 AUTOGAME_MCP_HOST=127.0.0.1", HTTP_HOST)
    app.run(transport="streamable-http", host=HTTP_HOST, port=HTTP_PORT,
            streamable_http_path=HTTP_PATH,
            transport_security=TransportSecuritySettings(
                enable_dns_rebinding_protection=True,
                allowed_hosts=HTTP_ALLOWED_HOSTS))


def _start_guard() -> None:
    """启动人工接管保护，并注册全局急停热键（幂等，失败不阻服务启动）。

    `start_stop_listener` 以前全仓库零调用，导致 F12 急停实际从未生效；
    而 `wait_if_paused` 需要一个“暂停中彻底停”的出口，所以在这里兜底注册。
    """
    try:
        cfg = cfgmod.load_config()
    except Exception as e:  # noqa: BLE001 配置缺失不能阻住服务
        log.warning("读取配置失败，人工接管保护与急停热键使用默认值: %s", e)
        cfg = {}
    get_guard(cfg.get("human_guard", {})).start()
    try:
        user_input.start_stop_listener(cfg.get("input", {}).get("stop_hotkey", "f12"))
    except Exception as e:  # noqa: BLE001
        log.warning("急停热键注册失败，F12 不可用: %s", e)


def main() -> None:
    log.info("MCP server 启动 transport=%s pid=%d python=%s 项目根=%s 日志=%s",
             TRANSPORT, os.getpid(), platform.python_version(),
             runlog.PROJECT_ROOT, runlog.log_path())
    _install_quit_handler()
    _start_guard()
    try:
        if TRANSPORT == "stdio":
            app.run(transport="stdio")
        elif TRANSPORT in ("http", "streamable-http"):
            _run_streamable_http()
        else:
            raise SystemExit(
                f"不支持的 AUTOGAME_MCP_TRANSPORT={TRANSPORT}（可选 stdio / streamable-http）")
    except KeyboardInterrupt:
        log.info("MCP server 中断（Ctrl-C）pid=%d", os.getpid())
    except BaseException:  # noqa: BLE001 其余异常连 traceback 一起留痕
        log.exception("MCP server 异常退出 pid=%d", os.getpid())
        raise
    finally:
        log.info("MCP server 结束 pid=%d", os.getpid())


if __name__ == "__main__":
    main()
