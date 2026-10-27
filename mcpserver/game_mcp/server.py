"""问道游戏识别 · MCP Server 入口（MCPServer）。

只暴露「看」的能力，共 6 个只读工具：

  · ping         连通性自检
  · list_windows 枚举桌面窗口（定位游戏窗口）
  · capture      截一帧存到 debug/
  · ocr          整图/区域 OCR，返回文字与坐标
  · layout       区域融合结果（通用检测实体 + OCR 兜底文本）
  · detect       通用检测（COCO 模型）单独跑一次，看实体

动作类（点击/按键）与业务流（登录/回主界面/任务）都已移除，保留的是三层架构本身：
客户端 → host（HTTP 门面）→ mcpserver（MCP 工具）。要重新长功能，往这里加工具即可。

内部统一复用 BotContext（core/engine.py）；窗口靠 config.window.title_keywords 定位。
两种传输（由 AUTOGAME_MCP_TRANSPORT 选择）：
  · stdio（默认）：`python -m game_mcp.server`，由 Java host 作为子进程拉起；
  · streamable-http：常驻监听，供容器里的 host 通过 AUTOGAME_MCP_URL 连接。
    因为要操作 macOS 本机窗口，这个进程只能跑在宿主机，不能放进 Linux 容器。
    同一个 HTTP 服务上还挂了一个 GET /frame.jpg：当前画面快照，给人看，
    不进工具清单、不发给模型。
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
from .core.engine import BotContext
from .core.window import find_game_window, list_windows as _list_windows
from .vision.screen import detect_general

app = MCPServer("wendao-game", version="0.1.0")

log = runlog.get_logger("server")


class WindowNotFound(RuntimeError):
    """当前没有可用的游戏窗口（未启动 / 最小化 / 在其他桌面）。

    属于预期状态而非程序缺陷：`_traced` 统一把它转成 `ok=false`，
    省掉每个工具各写一遍 `try/except RuntimeError` 的样板。
    """


def _ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


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

    工具全是只读的，不存在写操作竞争，所以这里只管埋点与异常兜底：
    返回 dict 的工具把异常转成 ok=false，返回 list/str 的工具原样抛（签名对不上）。
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
            if _returns_dict(sig):
                return _err(f"{name} 异常: {type(e).__name__}: {e}")
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


def _make_ctx() -> BotContext:
    """按当前配置与前台游戏窗口构造 BotContext；无窗口时抛 WindowNotFound。"""
    cfg = cfgmod.load_config()
    win = _find_win(cfg)
    if win is None:
        log.warning("未找到游戏窗口（关键词=%s），请先手动打开游戏",
                    cfg.get("window", {}).get("title_keywords"))
        raise WindowNotFound("未找到游戏窗口（请手动打开游戏，或确认窗口不在最小化/其他桌面）")
    log.debug("命中游戏窗口 %s", win)
    return BotContext(cfg, win)


def _ok(**kw) -> dict:
    return {"ok": True, **kw}


def _err(msg: str) -> dict:
    return {"ok": False, "error": msg}


# ---------- 识别能力 ----------
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
    """需要「看一眼画面」（留档、给人看）时用：截取游戏窗口并存到 debug/，返回 path/width/height/scale。

    只读无副作用；窗口没打开时返回 ok=false。只想知道画面里有哪些文字用 ocr 更快。
    """
    ctx = _make_ctx()
    img = ctx.grab()
    path = cap.debug_save(img, tag, ctx.debug_dir)
    return _ok(path=path, width=img.shape[1], height=img.shape[0], scale=ctx.win.scale)


@tool
def ocr(keyword: Optional[str] = None, region: Optional[List[int]] = None) -> dict:
    """需要知道画面里有哪些文字、或拿文字坐标时用：返回 count 与 texts[{text,confidence,box,cx,cy}]。

    keyword 非空时只保留包含该词的文本（比较时忽略空格，“师门任务”能命中“师门 任务”）。
    region 传 [x1,y1,x2,y2] 时只识别该区域（弹窗/面板内部），坐标仍回换算到整图。
    只读无副作用；识别不出文字时返回 count=0，不是错误。
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
    """需要一次拿到「哪里有实体、哪一行写了什么」时用：返回 count 与
    regions[{kind,source,text,box,cx,cy,confidence}]。

    kind 取 entity（通用检测到的游戏实体，只读锚点）或 label（OCR 按行聚合的文本）；
    source 取 general / ocr。只读无副作用；通用检测未就绪时等价于 OCR 按行聚合的文本。
    """
    ctx = _make_ctx()
    regions = ctx.analyze()
    return _ok(count=len(regions), regions=[
        {**r.to_dict(), "confidence": round(r.confidence, 3)} for r in regions
    ])


@tool
def detect(classes: Optional[List[str]] = None) -> dict:
    """需要按图案找游戏实体（人物/NPC）时用：返回 count、model_classes 与
    detections[{label,kind,layer,confidence,box,cx,cy}]。

    classes 非空时只保留匹配的模型类别名（如 person），不区分大小写；为空时用
    general.classes 白名单。结果一律是 kind=entity 的只读锚点。
    复用会话上已建好的检测器（权重懒加载且实例级缓存），不会每次调用都重新读盘。
    权重缺失时返回 ok=false。只读无副作用。
    """
    ctx = _make_ctx()
    dets = detect_general(ctx, ctx.grab(), classes=classes)
    if not dets and ctx.general._load_error:
        return _err("YOLO 加载失败 general: " + ctx.general._load_error)
    return _ok(count=len(dets),
               model_classes={"general": ctx.general.class_names},
               detections=[
                   {"label": d.label, "kind": d.kind, "layer": d.layer,
                    "confidence": round(d.confidence, 3),
                    "box": list(d.box), "cx": round(d.cx), "cy": round(d.cy)}
                   for d in dets
               ])


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
    """当前游戏画面快照（JPEG），客户端右侧面板「看一眼画面」用的。

    刻意不注册成 MCP 工具：它不参与识别，也不该出现在模型看到的工具清单里。
    走独立线程、不落盘（debug/ 只在 capture 工具显式调用时写），每帧都是一次性字节流。
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


def main() -> None:
    log.info("MCP server 启动 transport=%s pid=%d python=%s 项目根=%s 日志=%s",
             TRANSPORT, os.getpid(), platform.python_version(),
             runlog.PROJECT_ROOT, runlog.log_path())
    _install_quit_handler()
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
