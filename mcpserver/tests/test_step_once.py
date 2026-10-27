"""单步推进（vision/llm.step_once）的回归用例。

这次把「一次工具调用内部自己转 120 步」改成「一次调用只走一步，循环由 host 的
轮数上限驱动」，下面几条就是改动后必须锁住的性质：

  1. 一次调用只发一组动作，绝不自己转第二圈（否则又变成内层大脑）；
  2. 返回值必须带过程字段（scene / reason / chosen / executed / done / note），
     否则外层模型还是瞎子，只能看到一个数字；
  3. 窗口不在前台 → executed=0 且有 note，不假装成功；
  4. done=true 就清空推进状态，下次从头计数；
  5. 绕圈记录必须跨调用保留（这正是它从局部变量提到模块级的原因）。
"""
import cv2

import support
from game_mcp.core.engine import BotContext
from game_mcp.vision import llm as V

# 样例帧 dialog_options.png 里的一个面板内选项（见 test_vision_gate）
_OPTION = "【师门】师门任务"

_CTX = None
_IMG = None
_REGIONS = None


def _setup():
    """真跑一遍四层融合拿真实候选，只做一次（OCR+检测约 3s）。"""
    global _CTX, _IMG, _REGIONS
    if _CTX is not None:
        return _CTX
    cfg = support.load_test_config()
    cfg["timing"] = {"step_pause": 0, "idle_pause": 0, "click_pause": 0}
    # 用例只验单步语义：门控关掉（另有 test_vision_gate 覆盖），失败样本不落盘
    cfg.setdefault("vision", {}).update({"gate_by_top_layer": False,
                                        "collect_failed_shots": False})
    _CTX = BotContext(cfg, None)                                    # 离线用图，不需要窗口
    _IMG = cv2.imread(support.frame_path("dialog_options.png"))
    assert _IMG is not None, "样例帧读不出来"
    _REGIONS = _CTX.analyze(_IMG, _CTX.ocr(_IMG))
    return _CTX


class _FakeInput:
    """只记点击，用来验「到底发出去了几下」。"""

    def __init__(self, frontmost: bool = True):
        self.frontmost = frontmost
        self.clicks = []

    def click(self, x, y, **kw):
        if not self.frontmost:
            return False
        self.clicks.append((x, y))
        return True


def _ctx(picked=(), frontmost: bool = True, done: bool = False, choose=None):
    """造一个只认 picked 的假 ctx：模型不真调，感知/分流/执行全走真代码。"""
    ctx = _setup()
    ctx.grab = lambda: _IMG
    ctx.ocr = lambda img=None, region=None: []
    ctx.analyze = lambda img=None, results=None, layers=None: _REGIONS
    ctx.guard = lambda max_block_sec=0.0: True
    ctx.guard_generation = lambda: 0
    ctx.input = _FakeInput(frontmost)

    def default_choose(cands, goal="", context=None, size=None):
        ids = [c["id"] for c in cands if c["text"] in picked]
        return {"scene": "dialog", "reason": "用例", "choose": ids,
                "done": done, "other": None}

    ctx.advisor.choose = choose or default_choose
    return ctx


def test_one_call_sends_exactly_one_group():
    """一次调用只走一步：点一下、带过程、不自己循环。"""
    V.reset_step_state("t_one")
    ctx = _ctx(picked=(_OPTION,))
    out = V.step_once(ctx, "做师门任务", state_key="t_one")

    assert out["ok"] is True and out["executed"] == 1, out
    assert len(ctx.input.clicks) == 1, f"一次调用发了 {len(ctx.input.clicks)} 次点击"
    assert out["step"] == 1 and out["done"] is False
    # 过程字段：外层模型就是靠这些判断下一步，缺一个就又变瞎
    for field in ("scene", "reason", "chosen", "clickable", "context"):
        assert out.get(field), f"缺少过程字段 {field}: {out}"
    assert [c["text"] for c in out["chosen"]] == [_OPTION]


def test_step_counter_accumulates_across_calls():
    """步数跨调用累加：循环归调用方，计数得由我们记住。"""
    V.reset_step_state("t_count")
    first = V.step_once(_ctx(picked=(_OPTION,)), "g", state_key="t_count")
    second = V.step_once(_ctx(picked=(), choose=lambda cands, goal="", context=None, size=None: {
        "scene": "dialog", "reason": "", "choose": [], "done": False, "other": None,
    }), "g", state_key="t_count")
    assert (first["step"], second["step"]) == (1, 2), (first["step"], second["step"])


def test_loop_guard_survives_across_calls():
    """绕圈记录跨调用保留：第二次调用不能再推同一个会绕回来的选项。"""
    V.reset_step_state("t_loop")
    first = V.step_once(_ctx(picked=(_OPTION,)), "g", state_key="t_loop")
    assert first["executed"] == 1

    seen = []

    def spy(cands, goal="", context=None, size=None):
        seen.extend(c["text"] for c in cands)
        return {"scene": "dialog", "reason": "", "choose": [], "done": False, "other": None}

    second = V.step_once(_ctx(choose=spy), "g", state_key="t_loop")
    assert _OPTION not in seen, f"绕圈记录没跨调用保留，{_OPTION} 仍在候选里"
    assert second["executed"] == 0 and second["note"], "没执行时必须给出原因"


def test_not_frontmost_reports_zero_executed():
    """窗口不在前台：一下都不发，也不假装成功。"""
    V.reset_step_state("t_front")
    ctx = _ctx(picked=(_OPTION,), frontmost=False)
    out = V.step_once(ctx, "g", state_key="t_front")
    assert ctx.input.clicks == [], "窗口不在前台却发出了点击"
    assert out["executed"] == 0 and out["note"], out


def test_idle_gap_starts_a_new_task():
    """隔很久才来一步：按新任务重算，不拿上次的绕圈记录误导。"""
    V.reset_step_state("t_idle")
    assert V.step_once(_ctx(picked=(_OPTION,)), "g", state_key="t_idle")["step"] == 1

    V.step_state("t_idle")["ts"] -= 999   # 假装中间静默了 999s（人走开了）
    out = V.step_once(_ctx(picked=(_OPTION,)), "g", state_key="t_idle")
    assert out["step"] == 1, "静默很久后没有当成新任务"
    assert out["executed"] == 1, out


def test_done_clears_step_state():
    """done=true 清空推进状态：同一任务下次调用从第 1 步重新数。"""
    V.reset_step_state("t_done")
    assert V.step_once(_ctx(picked=(_OPTION,)), "g", state_key="t_done")["step"] == 1

    finished = V.step_once(_ctx(done=True), "g", state_key="t_done")
    assert finished["done"] is True and finished["executed"] == 0, finished
    assert V.step_state("t_done")["steps"] == 0, "done 之后推进状态没清空"

    again = V.step_once(_ctx(picked=(_OPTION,)), "g", state_key="t_done")
    assert again["step"] == 1, "done 之后步数没有归零重算"
