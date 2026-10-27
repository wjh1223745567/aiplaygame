"""MCP 工具入口护栏的回归用例：写操作串行、异常兜底、关键词/节奏可配置。

都是「出错时很难查」的地方，用最少的桩把行为钉住：
  1. 一次单步推进（daily_task）期间另一路调 click_point，必须立刻拿到 busy=true，
     不能排队干等，更不能两路同时去抢鼠标；
  2. 工具抛异常时，返回 dict 的转成 ok=false（模型能读懂），返回 list/str 的照旧抛；
  3. 关键词与等待时间以 config 为准，代码里的常量只是缺省值。
"""
import threading

import support  # noqa: F401
from game_mcp import server as srv
from game_mcp.tools import game as game_tools


def test_returns_dict_detection():
    import inspect
    assert srv._returns_dict(inspect.signature(srv.click_point)) is True
    # ping 返回 str、list_windows 返回 List[dict]：塞不进 dict，不能改写成 ok=false
    assert srv._returns_dict(inspect.signature(srv.ping)) is False
    assert srv._returns_dict(inspect.signature(srv.list_windows)) is False


def test_exception_turned_into_ok_false():
    def boom() -> dict:
        raise ValueError("内部炸了")

    r = srv._traced(boom)()
    assert r["ok"] is False and "内部炸了" in r["error"]


def test_exception_still_raised_for_list_tools():
    def boom() -> list:
        raise ValueError("内部炸了")

    try:
        srv._traced(boom)()
    except ValueError:
        return
    raise AssertionError("返回 list 的工具不该被改写成 dict")


def test_write_tool_rejected_while_another_runs():
    """另一路线程正持锁跑长任务时，click_point 立刻回 busy。"""
    result = {}

    def call():
        result.update(srv.click_point(1, 2))

    srv._WRITE_LOCK.acquire()
    srv._running.append("daily_task")
    try:
        t = threading.Thread(target=call)
        t.start()
        t.join(timeout=5)
    finally:
        srv._running.pop()
        srv._WRITE_LOCK.release()

    assert result.get("busy") is True, f"并发写操作没被拦住: {result}"
    assert result.get("ok") is False


def test_write_lock_is_reentrant():
    """工具实现内部再调受管控的工具，不能把自己锁死。"""
    srv._WRITE_LOCK.acquire()
    try:
        assert srv._WRITE_LOCK.acquire(blocking=False) is True
        srv._WRITE_LOCK.release()
    finally:
        srv._WRITE_LOCK.release()


def test_keywords_and_timing_come_from_config():
    cfg = {"keywords": {"battle_hints": ["自定义"]}, "timing": {"back_step_wait": 0.25}}
    assert game_tools._kw(cfg, "battle_hints") == ["自定义"]
    assert game_tools._sec(cfg, "back_step_wait") == 0.25
    # 没配就回落到代码默认值，不能变 None
    assert game_tools._kw({}, "battle_hints")
    assert game_tools._sec(None, "back_step_wait") > 0


def test_main_scene_judgement_uses_config():
    cfg = {"keywords": {"main_scene_strong": ["自定义强特征"]}}
    assert game_tools._main_scene_hit(["自定义强特征"], cfg) is True
    # 换掉强特征后，原来那套词不再有强判定效力，只按弱特征数量算
    assert game_tools._main_scene_hit(["展开"], cfg) is False
