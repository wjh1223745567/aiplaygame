"""游戏功能 · 师门任务与集市扫货（单步推进）。

每次调用只推进一步：识别当前画面 → 视觉模型从可点项里选 → 用我方坐标执行，
把 scene/reason/chosen/executed/done 全回给调用方，循环由 host 的轮数上限驱动。
以前这里内部自己转 120 步、只回一个 steps 数字，外层模型看不到过程也无法纠偏。
"""
from __future__ import annotations

from ..core.engine import BotContext
from ..vision.llm import step_once

DAILY_GOAL = ("完成问道的师门任务：在任务追踪或「任务」面板找到「师门任务」并点它自动寻路接取，"
              "按对话与战斗提示一步步推进，直到任务提交、奖励领完")
MARKET_GOAL = ("在集市/商铺中按目标条件扫货：先打开集市列表，识别可购买条目，"
               "按价格与需求选择合适的物品购买，买完或无合适物品时结束")


def daily_task(ctx: BotContext, goal: str = "", max_steps: int = 1) -> dict:
    """推进一步师门任务（max_steps>1 只给命令行调试用，MCP 工具默认单步）。"""
    return _run(ctx, "daily_task", "任务", DAILY_GOAL, goal, max_steps,
                "骨架：流程依赖实机标定，必要时人工确认结果")


def market_scan(ctx: BotContext, goal: str = "", max_steps: int = 1) -> dict:
    """推进一步集市扫货（OCR 读列表 + 视觉模型选一步）。"""
    return _run(ctx, "market_scan", "扫货", MARKET_GOAL, goal, max_steps,
                "骨架：筛选规则（关键字/价格阈值/数量）待实机标定后经 goal 传入")


def _run(ctx: BotContext, task: str, label: str, default_goal: str,
         goal: str, max_steps: int, hint: str) -> dict:
    """两个骨架共用的单步入口：goal 为空时用内置目标，异常统一转 ok=false。

    返回的是 step_once 的过程字段（scene/reason/chosen/executed/done/note），
    外加 task 与 hint；不再返回「跑了几步」这种对外层无意义的汇总值。
    label 只用于错误文案（「任务单步异常」/「扫货单步异常」）。
    """
    goal = goal or default_goal
    out: dict | None = None
    try:
        # 默认 max_steps=1：工具只走一步，循环交给调用方（host 的 maxRounds）
        for _ in range(max(1, int(max_steps))):
            out = step_once(ctx, goal, state_key=task)
            if out.get("done") or not out.get("ok", True):
                break
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": f"{label}单步异常: {type(e).__name__}: {e}"}
    result = dict(out or {})
    result.setdefault("ok", True)
    result["task"] = task
    result["hint"] = hint
    return result
