"""问道游戏自动化 · MCP Server 入口（MCPServer, stdio）。

通过 MCP 暴露两类工具：
  · 基础能力：ping / list_windows / capture / ocr / detect / click_point /
             click_text / press_key / advise
  · 游戏功能：open_game / close_game / game_status / login / daily_task / market_scan（见 tools/）

内部统一复用 BotContext（core/engine.py）；窗口靠 config.window.title_keywords 定位。
运行：`python -m game_mcp.server`（stdio 传输，由 Java client 拉起）。
"""
from __future__ import annotations

import functools
import inspect
import os
import platform
import signal
import time
from typing import List, Optional

from mcp.server.mcpserver import MCPServer

from . import config as cfgmod
from .core import capture as cap
from .core import log as runlog
from .core import input as user_input
from .core.engine import BotContext
from .core.window import find_game_window, list_windows as _list_windows
from .vision.ocr import ocr_image
from .vision.yolo import YoloDetector
from .tools import game as game_tools
from .tools import daily as daily_tools

app = MCPServer("wendao-game", version="0.1.0")

log = runlog.get_logger("server")


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def tool(fn):
    """注册 MCP 工具，并记录参数、耗时、成败与异常。

    做法：先照原函数原样注册（schema 完全由原签名与原返回注解推导，不受影响），
    再把 Tool 的执行入口 `fn` 换成带日志的包装——这样既能统一埋点，
    也不会因为包装函数改变 structured output 的推导结果。
    """
    app.tool()(fn)
    registered = app._tool_manager.get_tool(fn.__name__)
    if registered is not None:
        registered.fn = _traced(fn)
    return fn


def _traced(fn):
    """给工具函数套一层日志：一次调用一条日志，异常先留痕再原样抛出。"""
    sig = inspect.signature(fn)
    name = fn.__name__

    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            shown = runlog.brief(dict(sig.bind(*args, **kwargs).arguments), 240)
        except TypeError:  # 参数绑定失败也要留痕，再让原函数自己报错
            shown = runlog.brief({"args": args, "kwargs": kwargs}, 240)
        started = time.perf_counter()
        try:
            result = fn(*args, **kwargs)
        except Exception as e:  # noqa: BLE001 照旧抛给 MCP，但先留痕
            log.error("工具 %s 异常 %dms 参数=%s: %s",
                      name, _ms(started), shown, e, exc_info=True)
            raise
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
    """按当前配置与前台游戏窗口构造 BotContext；无窗口时抛错由工具捕获。"""
    cfg = cfgmod.load_config()
    user_input.set_dry_run(dry_run)
    win = _find_win(cfg)
    if win is None:
        log.warning("未找到游戏窗口（关键词=%s），请先 open_game 或手动打开",
                    cfg.get("window", {}).get("title_keywords"))
        raise RuntimeError("未找到游戏窗口（请先 open_game 或手动打开游戏）")
    log.debug("命中游戏窗口 %s dry_run=%s", win, dry_run)
    return BotContext(cfg, win)


def _ok(**kw) -> dict:
    return {"ok": True, **kw}


def _err(msg: str) -> dict:
    return {"ok": False, "error": msg}


# ---------- 基础能力 ----------
@tool
def ping() -> str:
    """连通性自检：往返一次即证明 host->client->server 通路正常。"""
    return "pong"


@tool
def list_windows() -> List[dict]:
    """枚举屏幕上所有可见窗口（用于确认游戏窗口标题/尺寸）。"""
    return [{
        "window_id": w.window_id, "owner": w.owner, "title": w.title,
        "x": w.x, "y": w.y, "width": w.width, "height": w.height,
        "scale": w.scale, "pid": w.pid,
    } for w in _list_windows()]


@tool
def capture(tag: str = "capture") -> dict:
    """截取游戏窗口并保存到 debug/，返回路径与尺寸。"""
    try:
        ctx = _make_ctx()
    except RuntimeError as e:
        return _err(str(e))
    img = ctx.grab()
    path = cap.debug_save(img, tag, ctx.debug_dir)
    return _ok(path=path, width=img.shape[1], height=img.shape[0], scale=ctx.win.scale)


@tool
def ocr(keyword: Optional[str] = None) -> dict:
    """对游戏窗口做 OCR；keyword 非空时只返回包含该词的文本。"""
    try:
        ctx = _make_ctx()
    except RuntimeError as e:
        return _err(str(e))
    img = ctx.grab()
    results = ocr_image(img, min_confidence=ctx.ocr_min_conf)
    if keyword:
        results = [r for r in results if keyword in r.text.replace(" ", "")]
    return _ok(count=len(results), texts=[
        {"text": r.text, "confidence": round(r.confidence, 3),
         "box": list(r.box), "cx": round(r.cx), "cy": round(r.cy)}
        for r in results
    ])


@tool
def detect(classes: Optional[List[str]] = None) -> dict:
    """YOLO 目标检测（临时强制启用，无需改 config.yolo.enabled）。"""
    try:
        cfg = cfgmod.load_config()
        win = _find_win(cfg)
        if win is None:
            return _err("未找到游戏窗口")
        img = cap.capture_window(win)
        ycfg = dict(cfg.get("yolo", {}))
        ycfg["enabled"] = True
        det = YoloDetector(ycfg)
        dets = det.detect(img, classes=classes)
        if det._load_error:
            return _err(f"YOLO 加载失败: {det._load_error}")
        return _ok(count=len(dets), detections=[
            {"label": d.label, "confidence": round(d.confidence, 3),
             "box": list(d.box), "cx": round(d.cx), "cy": round(d.cy)}
            for d in dets
        ])
    except Exception as e:  # noqa: BLE001
        return _err(f"检测异常: {e}")


@tool
def click_point(x: float, y: float, dry_run: bool = False) -> dict:
    """点击窗口内像素坐标 (x,y)。dry_run=True 只打印不执行。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    ctx.click_point(x, y)
    return _ok(clicked=[x, y], dry_run=dry_run)


@tool
def click_text(text: str, dry_run: bool = False) -> dict:
    """OCR 定位包含 text 的文本并点击其中心。dry_run=True 只打印不执行。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    hit = ctx.click_text(text)
    if not hit:
        return _err(f"未找到文本: {text}")
    return _ok(clicked_text=text, dry_run=dry_run)


@tool
def press_key(key: str, dry_run: bool = False) -> dict:
    """按下按键（esc/enter/space/tab 或单字符）。dry_run=True 只打印不执行。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    ctx.input.press_key(key)
    return _ok(key=key, dry_run=dry_run)


@tool
def advise(goal: str = "", dry_run: bool = True) -> dict:
    """看当前画面让大模型单轮决策（默认 dry_run 只推荐不点击）。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    if not ctx.advisor.api_key:
        return _err("未配置 llm.api_key")
    from .vision.llm import LlmError
    img = ctx.grab()
    results = ctx.ocr(img)
    try:
        plan = ctx.advisor.advise(img, results, goal=goal or "推进当前任务")
    except LlmError as e:
        return _err(f"大模型调用失败: {e}")
    return _ok(scene=plan.get("scene"), reason=plan.get("reason"),
               done=plan.get("done"), actions=plan.get("actions", []),
               candidates=plan.get("candidates", []), dry_run=dry_run)


# ---------- 游戏功能（见 tools/） ----------
@tool
def open_game() -> dict:
    """启动或激活问道客户端并等待游戏窗口出现。"""
    try:
        return game_tools.open_game(cfgmod.load_config())
    except Exception as e:  # noqa: BLE001
        return _err(f"打开游戏失败: {e}")


@tool
def close_game(force: bool = False, timeout: float = 2.5) -> dict:
    """关闭问道客户端：先正常退出（可存档），超时后依次升级 SIGTERM / SIGKILL。

    该客户端对 Cmd+Q / SIGTERM 都不响应，只有 SIGKILL 生效，故各级等待都很短
    （默认 2.5s -> 1.5s -> 2s）。force=True 跳过优雅退出直接强杀（会丢失未保存进度）。
    """
    try:
        return game_tools.close_game(cfgmod.load_config(), timeout=timeout, force=force)
    except Exception as e:  # noqa: BLE001
        return _err(f"关闭游戏失败: {e}")


@tool
def game_status() -> dict:
    """检测游戏是否已打开（running / window_visible / pids）。"""
    try:
        return _ok(**game_tools.is_game_running(cfgmod.load_config()))
    except Exception as e:  # noqa: BLE001
        return _err(f"检测失败: {e}")


@tool
def login(timeout: float = 120.0, auto_open: bool = True) -> dict:
    """自动检测游戏是否打开（未打开则自动启动） -> 点击「登录游戏」 -> 等主场景。"""
    try:
        return game_tools.login(cfgmod.load_config(), timeout=timeout, auto_open=auto_open)
    except Exception as e:  # noqa: BLE001
        return _err(f"登录失败: {e}")


@tool
def daily_task(goal: str = "", max_steps: int = 120, dry_run: bool = False) -> dict:
    """LLM 驱动的每日任务循环（骨架，需实机标定具体流程）。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    return daily_tools.daily_task(ctx, goal=goal, max_steps=max_steps)


@tool
def market_scan(goal: str = "", max_steps: int = 120, dry_run: bool = False) -> dict:
    """集市扫货（骨架：OCR 读列表 + LLM 兜底决策，需实机标定筛选规则）。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
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


def main() -> None:
    log.info("MCP server 启动 transport=stdio pid=%d python=%s 项目根=%s 日志=%s",
             os.getpid(), platform.python_version(),
             runlog.PROJECT_ROOT, runlog.log_path())
    _install_quit_handler()
    try:
        app.run(transport="stdio")
    except KeyboardInterrupt:
        log.info("MCP server 中断（Ctrl-C）pid=%d", os.getpid())
    except BaseException:  # noqa: BLE001 其余异常连 traceback 一起留痕
        log.exception("MCP server 异常退出 pid=%d", os.getpid())
        raise
    finally:
        log.info("MCP server 结束 pid=%d", os.getpid())


if __name__ == "__main__":
    main()
