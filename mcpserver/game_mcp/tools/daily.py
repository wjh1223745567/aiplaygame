"""游戏功能 · 师门任务与集市扫货（LLM 驱动骨架）。

问道的师门任务与集市筛选规则尚未实机标定，这里以「识别→大模型连续决策→执行」
循环推进（复用 vision/llm.run_advisor_loop），goal 决定任务方向。
返回轮数与状态，供 host 判断是否需人工介入。
"""
from __future__ import annotations

from ..core.engine import BotContext
from ..vision.llm import run_advisor_loop, LlmError

DAILY_GOAL = ("完成问道的师门任务：在任务追踪或「任务」面板找到「师门任务」并点它自动寻路接取，"
              "按对话与战斗提示一步步推进，直到任务提交、奖励领完")
MARKET_GOAL = ("在集市/商铺中按目标条件扫货：先打开集市列表，识别可购买条目，"
               "按价格与需求选择合适的物品购买，买完或无合适物品时结束")


def daily_task(ctx: BotContext, goal: str = "", max_steps: int = 120) -> dict:
    """LLM 驱动的师门任务循环。"""
    return _run(ctx, "daily_task", "任务", DAILY_GOAL, goal, max_steps,
                "骨架：流程依赖实机标定，必要时人工确认结果")


def market_scan(ctx: BotContext, goal: str = "", max_steps: int = 120) -> dict:
    """集市扫货骨架：OCR 读列表 + LLM 连续决策兜底。"""
    return _run(ctx, "market_scan", "扫货", MARKET_GOAL, goal, max_steps,
                "骨架：筛选规则（关键字/价格阈值/数量）待实机标定后经 goal 传入")


def _run(ctx: BotContext, task: str, label: str, default_goal: str,
         goal: str, max_steps: int, note: str) -> dict:
    """两个骨架共用的循环入口：goal 为空时用内置目标，异常统一转 ok=false。

    label 只用于错误文案（保持对外提示仍然是「任务循环异常」/「扫货循环异常」）。
    """
    try:
        steps = run_advisor_loop(ctx, goal=goal or default_goal, max_steps=max_steps)
    except LlmError as e:
        return {"ok": False, "error": f"大模型调用失败: {e}"}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": f"{label}循环异常: {e}"}
    return {"ok": True, "task": task, "steps": steps, "note": note}
