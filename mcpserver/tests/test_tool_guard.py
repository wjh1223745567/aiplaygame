"""MCP 工具入口护栏的回归用例。

工具现在全是只读的（6 个），要守住的是入口本身的两条约定：
  1. 返回注解决定异常怎么处理：返回 dict 的转成 ok=false（调用方看得懂），
     返回 list/str 的不能改写（签名对不上，只能原样抛）；
  2. 工具清单必须正好是这 6 个识别类工具——多出来的名字会进客户端提示，
     少了名字客户端点不到。
"""
import inspect

import support  # noqa: F401
from game_mcp import server as srv

EXPECTED_TOOLS = {"ping", "list_windows", "capture", "ocr", "layout", "detect"}


def test_returns_dict_detection():
    assert srv._returns_dict(inspect.signature(srv.capture)) is True
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


def test_tool_list_is_readonly_only():
    """只暴露识别类工具：动作/业务类工具一旦回来，这个断言会先红。"""
    names = {t.name for t in srv.app._tool_manager.list_tools()}
    assert names == EXPECTED_TOOLS, f"工具清单变了: {sorted(names)}"


def test_write_lock_and_pause_hooks_are_gone():
    """写锁与人工接管暂停随动作类工具一起删除，入口不该再留半截状态。"""
    assert not hasattr(srv, "_WRITE_LOCK")
    assert not hasattr(srv, "_pause_block")
    assert not hasattr(srv, "click_point")
