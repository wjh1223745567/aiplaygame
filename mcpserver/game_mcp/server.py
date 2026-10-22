"""问道游戏自动化 · MCP Server 入口（MCPServer, stdio）。

通过 MCP 暴露两类工具：
  · 基础能力：ping / list_windows / capture / ocr / detect / click_point /
             click_text / press_key / advise
  · 游戏功能：open_game / game_status / login / daily_task / market_scan（见 tools/）

内部统一复用 BotContext（core/engine.py）；窗口靠 config.window.title_keywords 定位。
运行：`python -m game_mcp.server`（stdio 传输，由 Java client 拉起）。
"""
from __future__ import annotations

from typing import List, Optional

from mcp.server.mcpserver import MCPServer

from . import config as cfgmod
from .core import capture as cap
from .core import input as user_input
from .core.engine import BotContext
from .core.window import find_game_window, list_windows as _list_windows
from .vision.ocr import ocr_image
from .vision.yolo import YoloDetector
from .tools import game as game_tools
from .tools import daily as daily_tools

app = MCPServer("wendao-game", version="0.1.0")


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
        raise RuntimeError("未找到游戏窗口（请先 open_game 或手动打开游戏）")
    return BotContext(cfg, win)


def _ok(**kw) -> dict:
    return {"ok": True, **kw}


def _err(msg: str) -> dict:
    return {"ok": False, "error": msg}


# ---------- 基础能力 ----------
@app.tool()
def ping() -> str:
    """连通性自检：往返一次即证明 host->client->server 通路正常。"""
    return "pong"


@app.tool()
def list_windows() -> List[dict]:
    """枚举屏幕上所有可见窗口（用于确认游戏窗口标题/尺寸）。"""
    return [{
        "window_id": w.window_id, "owner": w.owner, "title": w.title,
        "x": w.x, "y": w.y, "width": w.width, "height": w.height,
        "scale": w.scale, "pid": w.pid,
    } for w in _list_windows()]


@app.tool()
def capture(tag: str = "capture") -> dict:
    """截取游戏窗口并保存到 debug/，返回路径与尺寸。"""
    try:
        ctx = _make_ctx()
    except RuntimeError as e:
        return _err(str(e))
    img = ctx.grab()
    path = cap.debug_save(img, tag, ctx.debug_dir)
    return _ok(path=path, width=img.shape[1], height=img.shape[0], scale=ctx.win.scale)


@app.tool()
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


@app.tool()
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


@app.tool()
def click_point(x: float, y: float, dry_run: bool = False) -> dict:
    """点击窗口内像素坐标 (x,y)。dry_run=True 只打印不执行。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    ctx.click_point(x, y)
    return _ok(clicked=[x, y], dry_run=dry_run)


@app.tool()
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


@app.tool()
def press_key(key: str, dry_run: bool = False) -> dict:
    """按下按键（esc/enter/space/tab 或单字符）。dry_run=True 只打印不执行。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    ctx.input.press_key(key)
    return _ok(key=key, dry_run=dry_run)


@app.tool()
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
@app.tool()
def open_game() -> dict:
    """启动或激活问道客户端并等待游戏窗口出现。"""
    try:
        return game_tools.open_game(cfgmod.load_config())
    except Exception as e:  # noqa: BLE001
        return _err(f"打开游戏失败: {e}")


@app.tool()
def game_status() -> dict:
    """检测游戏是否已打开（running / window_visible / pids）。"""
    try:
        return _ok(**game_tools.is_game_running(cfgmod.load_config()))
    except Exception as e:  # noqa: BLE001
        return _err(f"检测失败: {e}")


@app.tool()
def login(timeout: float = 120.0, auto_open: bool = True) -> dict:
    """自动检测游戏是否打开（未打开则自动启动） -> 点击「登录游戏」 -> 等主场景。"""
    try:
        return game_tools.login(cfgmod.load_config(), timeout=timeout, auto_open=auto_open)
    except Exception as e:  # noqa: BLE001
        return _err(f"登录失败: {e}")


@app.tool()
def daily_task(goal: str = "", max_steps: int = 120, dry_run: bool = False) -> dict:
    """LLM 驱动的每日任务循环（骨架，需实机标定具体流程）。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    return daily_tools.daily_task(ctx, goal=goal, max_steps=max_steps)


@app.tool()
def market_scan(goal: str = "", max_steps: int = 120, dry_run: bool = False) -> dict:
    """集市扫货（骨架：OCR 读列表 + LLM 兜底决策，需实机标定筛选规则）。"""
    try:
        ctx = _make_ctx(dry_run=dry_run)
    except RuntimeError as e:
        return _err(str(e))
    return daily_tools.market_scan(ctx, goal=goal, max_steps=max_steps)


def main() -> None:
    app.run(transport="stdio")


if __name__ == "__main__":
    main()
