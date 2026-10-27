"""抓帧端点的回归用例（离线，不起 HTTP 服务）。

客户端右侧那块「当前画面」全靠这一个旁路端点，它有三个必须守住的性质：
  1. 走真代码路径产出一张能解码的 JPEG（客户端 imageio 读不出来就等于白屏）；
  2. 没有游戏窗口时返回 503 + 一句人话，而不是 200 空图或 500 堆栈；
  3. 不是 MCP 工具——工具的 description 会进模型提示词，截图不能从这条路渗进去。
"""
import asyncio

import cv2
import numpy as np

import support
from game_mcp import server


class _FakeCtx:
    """只实现 grab()：抓帧端点真正用到的只有这一个方法。"""

    def __init__(self, img):
        self.img = img

    def grab(self):
        return self.img


class _FakeRequest:
    """够 log.debug 用即可（真实请求里拿的是客户端 IP）。"""

    client = None


def _call_handler():
    return asyncio.run(server.frame_jpg(_FakeRequest()))


def test_frame_returns_decodable_jpeg():
    img = cv2.imread(support.frame_path("main_scene.png"))
    assert img is not None

    with _PatchedCtx(lambda: _FakeCtx(img)):
        response = _call_handler()

    assert response.status_code == 200
    assert response.media_type == "image/jpeg"
    assert response.headers["cache-control"] == "no-store"
    decoded = cv2.imdecode(np.frombuffer(_bytes(response), np.uint8), cv2.IMREAD_COLOR)
    assert decoded is not None, "客户端 ImageIO 读不出来这一帧"
    assert (decoded.shape[1], decoded.shape[0]) == (img.shape[1], img.shape[0])


def test_frame_without_window_is_503_with_reason():
    def boom():
        raise server.WindowNotFound("未找到游戏窗口（请手动打开游戏窗口）")

    with _PatchedCtx(boom):
        response = _call_handler()

    assert response.status_code == 503
    assert "未找到游戏窗口" in _bytes(response).decode("utf-8")


def test_frame_is_not_an_mcp_tool():
    names = {t.name for t in server.app._tool_manager.list_tools()}
    assert "frame" not in names and "frame_jpg" not in names
    # 端点仍然挂在 HTTP 应用上（route 在启动时才组装，这里只看注册表）
    paths = {getattr(r, "path", "") for r in server.app._custom_starlette_routes}
    assert server.FRAME_PATH in paths


def _bytes(response) -> bytes:
    return bytes(response.body)


class _PatchedCtx:
    """替掉 _make_ctx（不装 pytest，没用 monkeypatch fixture）。"""

    def __init__(self, factory):
        self.factory = factory
        self.original = None

    def __enter__(self):
        self.original = server._make_ctx
        server._make_ctx = lambda: self.factory()
        return self

    def __exit__(self, *_exc):
        server._make_ctx = self.original
        return False
