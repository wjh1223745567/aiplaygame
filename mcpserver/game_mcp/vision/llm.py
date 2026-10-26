"""免费大模型（OpenAI 兼容视觉接口）决策助手。

用途：把当前游戏截图 + OCR 文本交给多模态大模型，让它
  1) 判断当前处于什么界面（主场景/对话/战斗/过场/面板/加载…）
  2) 推荐下一步要执行的动作（点击文字 / 点击坐标 / 按键 / 等待）

零第三方依赖：仅用标准库 urllib 走 OpenAI `/chat/completions` 协议，
默认对接智谱 GLM-4V-Flash（永久免费）。更换任意兼容服务只需改 config.llm 三项。

动作 schema（模型须返回该 JSON）：
{
  "scene": "main|dialog|battle|cutscene|panel|loading|other",
  "reason": "一句话中文说明",
  "actions": [
    {"action": "click_text", "text": "前进"},
    {"action": "click_point", "x": 0.52, "y": 0.40},   // 归一化 0~1（相对整幅截图）
    {"action": "press", "key": "esc"},
    {"action": "wait", "sec": 2}
  ],
  "done": false
}
优先使用 click_text：坐标由我方 OCR 结果精确提供，比模型给像素更可靠。
"""
from __future__ import annotations

import base64
import json
import os
import re
import time
import urllib.error
import urllib.request
from typing import List, Optional, Tuple

import cv2
import numpy as np

from ..core import decision_log
from ..core.log import get_logger

_log = get_logger("vision.llm")

# 智谱开放平台 OpenAI 兼容端点（GLM-4V-Flash 永久免费）
DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4"
DEFAULT_MODEL = "glm-4v-flash"

SYSTEM_PROMPT = (
    "你是问道的老玩家兼自动决策器。你会收到：当前游戏截图 + 我方用「模板匹配 / YOLO 区域分类 / OCR」"
    "三层融合定位好的文字，分成【可点击项】(带编号 id) 与【画面其他信息】(只读)两部分。"
    "请按「正常游戏策略」从可点击项里选出应点击的 id(可多选、按点击顺序)。"
    "\n【候选】只给可点击项编号，你只能从中选 id；画面其他信息单独列出，只作判断依据，不要试图点击；"
    "也不得发明清单以外的文字。坐标由我方按 id 回填，你绝不输出坐标；只输出一个JSON对象，无多余文字或代码围栏。"
    "\n【输出字段】scene(main/dialog/battle/cutscene/panel/loading/other), reason(简短中文), "
    "choose(整数id数组，取自清单；无需点则空数组), "
    "other({\"action\":\"press|wait|none\",\"key\":\"esc|enter|space|tab\",\"sec\":秒}), "
    "done(目标已达成时true)。"
    "\n【正常游戏策略】"
    "\n1) 通用：始终朝「当前任务线索」推进，优先选最贴合目标、推进步数最少的项；同一次不要选多个相互冲突的项。"
    "\n2) 主场景：优先点「任务追踪」里师门任务等目标行(会自动寻路/交互，此类行若出现在可点击项里才可点，否则按其他信息的文字线索判断)；若有「领取/签到/奖励」等可领项先领取；不要打开无关面板。"
    "\n3) 对话：选能推进剧情的选项——优先与任务目标对应的NPC/给予/继续/前进/确定/传送；绝不选 放弃/取消/拒绝/不接/离开/下次再说。"
    "\n4) 战斗：优先选「自动/自动战斗」挂机；否则攻击与任务相关的怪/主怪；不要点 逃跑/防御/无关技能；战斗结束后清理奖励弹窗。"
    "\n5) 面板/弹窗：领完奖励后关闭(点 关闭/确定)；不在无关界面久留。"
    "\n6) 加载/过场：choose给空数组并用 other.wait，或等待画面稳定。"
    "\n7) 反循环：若提示某些选项已反复点击无效，绝不要再选它们，改选其他能真正推进任务的不同选项。"
)


class LlmError(RuntimeError):
    pass


def _timing(ctx, key: str, default: float) -> float:
    """从 config.timing 读节奏参数（缺失/非法则用默认值）。"""
    try:
        return float(ctx.cfg.get("timing", {}).get(key, default))
    except Exception:  # noqa: BLE001
        return default


def _img_to_data_url(img: np.ndarray, max_side: int = 1024) -> str:
    """BGR ndarray -> 缩放后的 jpeg data url。返回 (dataurl)；缩放比例由调用处不再需要（用归一化坐标）。"""
    h, w = img.shape[:2]
    scale = min(1.0, max_side / max(h, w))
    if scale < 1.0:
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
    if not ok:
        raise LlmError("截图编码为 jpeg 失败")
    b64 = base64.b64encode(buf.tobytes()).decode("ascii")
    return f"data:image/jpeg;base64,{b64}"


# 区域类型的中文名：只读上下文按「类型: 内容」渲染给模型
_KIND_CN = {"button": "按钮", "icon": "图标", "task": "任务",
            "dialog": "弹窗", "info": "信息", "label": "文本", "entity": "实体"}


def _region_text(region) -> str:
    """区域的可读文字：优先区内 OCR，实体用检测类别，模板区域用模板名兜底。"""
    text = (region.text or "").strip()
    if text:
        return text
    meta = region.meta or {}
    if region.kind == "entity":
        # 通用层只有类别（person/NPC…），没有文字：告诉模型「这里有个什么东西」
        return str(meta.get("general_label") or "目标")
    name = meta.get("template")
    return str(name) if name else _KIND_CN.get(region.kind, region.kind)


def _region_click_point(region) -> Tuple[int, int]:
    """点击点只由我方区域给出：模板命中中心 → 区内文字中心 → 框中心。"""
    if region.points:
        x, y = region.points[0]
        return int(x), int(y)
    return region.center


def build_candidates(regions, clickable_kinds=("button",), fallback: bool = True,
                     max_n: int = 40) -> Tuple[List[dict], List[str]]:
    """把融合后的区域拆成 (可点候选带 id, 只读上下文文本)。坐标只由我方产生。

    只有 `kind in clickable_kinds` 的区域才进候选并分配 id——信息/弹窗正文/任务追踪
    这类区域没有 id，模型不可能把它们点掉（这正是「正文里的『确定』二字被点」的根因修法）。
    `fallback=True` 时可点集合为空（模板未标定、YOLO 未训练、画面确实没有按钮），
    把 `label` 区域整体提升为可点候选，等价于改造前的纯文本候选，避免流程空转停摆。
    """
    kinds = {str(k) for k in (clickable_kinds or ())}
    clickable = [r for r in regions if r.kind in kinds]
    readonly = [r for r in regions if r.kind not in kinds]
    if not clickable and fallback:
        promoted = [r for r in readonly if r.kind == "label"]
        if promoted:
            _log.info("可点项为空，降级为纯文本候选（%d 项）", len(promoted))
            clickable = promoted
            readonly = [r for r in readonly if r.kind != "label"]
    cands: List[dict] = []
    seen = set()
    for r in clickable:
        text = _region_text(r)
        x, y = _region_click_point(r)
        key = (text, x, y)
        if not text or key in seen:
            continue
        seen.add(key)
        cands.append({"id": len(cands) + 1, "text": text, "x": x, "y": y,
                      "kind": r.kind, "source": r.source})
        if len(cands) >= max_n:
            break
    context: List[str] = []
    for r in readonly:
        # YOLO 区域常常只有类别、区内没文字（图标/工具栏/面板）：这种行没有信息量，
        # 只会把 prompt 塞满「信息: 信息」，直接不写。实体与模板区域保留。
        if r.kind != "entity" and not (r.text or "").strip() \
                and not (r.meta or {}).get("template"):
            continue
        text = _region_text(r)
        if text:
            context.append(f"{_KIND_CN.get(r.kind, r.kind)}: {text}")
    return cands, context


def _candidates_hint(cands: List[dict]) -> str:
    """生成给模型的编号选项清单：[id] (类型) text（不给坐标）。"""
    return "\n".join(
        f"[{c['id']}] ({_KIND_CN.get(c.get('kind', ''), c.get('kind') or '项')}) {c['text']}"
        for c in cands)


def _extract_json(text: str) -> Optional[dict]:
    """从模型返回文本中稳健地抠出第一个 JSON 对象（容忍 ```json 围栏与前后废话）。"""
    if not text:
        return None
    text = text.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fenced:
        text = fenced.group(1)
    start = text.find("{")
    if start == -1:
        return None
    # 从第一个 { 起做括号配平扫描
    depth = 0
    for i in range(start, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                candidate = text[start:i + 1]
                try:
                    return json.loads(candidate)
                except json.JSONDecodeError:
                    try:
                        return json.loads(candidate.replace("\n", " "))
                    except json.JSONDecodeError:
                        return None
    return None


class Advisor:
    """大模型决策助手。cfg 为整份 config dict，读取其中的 llm 段。"""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        llm = cfg.get("llm", {})
        self.enabled: bool = bool(llm.get("enabled", True))
        self.base: str = (llm.get("api_base") or DEFAULT_BASE).rstrip("/")
        self.model: str = llm.get("model") or DEFAULT_MODEL
        self.api_key: str = llm.get("api_key") or os.environ.get("LLM_API_KEY", "")
        self.timeout: float = float(llm.get("timeout", 30))
        self.max_side: int = int(llm.get("max_image_side", 1024))
        self.temperature: float = float(llm.get("temperature", 0.0))

    # ---------- 视觉分流配置（config.vision） ----------
    @property
    def clickable_kinds(self) -> List[str]:
        """哪些区域类型可以进入可点候选。现阶段只有 button。"""
        vision = self.cfg.get("vision", {}) or {}
        return list(vision.get("clickable_kinds") or ["button"])

    @property
    def empty_fallback(self) -> bool:
        """可点集合为空时是否降级为纯文本候选（等价于改造前行为）。"""
        vision = self.cfg.get("vision", {}) or {}
        return bool(vision.get("empty_clickable_fallback", True))

    # ---------- 底层调用 ----------
    def _chat(self, user_text: str, img: Optional[np.ndarray]) -> str:
        if not self.api_key:
            _log.warning("未配置 llm.api_key（或环境变量 LLM_API_KEY），大模型决策不可用")
            raise LlmError("未配置 llm.api_key（或环境变量 LLM_API_KEY）")
        content: List[dict] = [{"type": "text", "text": user_text}]
        if img is not None:
            content.append({
                "type": "image_url",
                "image_url": {"url": _img_to_data_url(img, self.max_side)},
            })
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": content},
            ],
            "temperature": self.temperature,
            "stream": False,
        }
        req = urllib.request.Request(
            f"{self.base}/chat/completions",
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.api_key}",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                body = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:  # 带上响应体便于排错
            detail = ""
            try:
                detail = e.read().decode("utf-8", "ignore")[:300]
            except Exception:  # noqa: BLE001
                pass
            _log.error("大模型 HTTP %d 失败 model=%s: %s", e.code, self.model, detail)
            raise LlmError(f"HTTP {e.code} 调用失败: {detail}") from e
        except urllib.error.URLError as e:
            _log.error("大模型网络错误 model=%s: %s", self.model, e.reason)
            raise LlmError(f"网络错误: {e.reason}") from e
        try:
            text = body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            _log.error("大模型返回结构异常 model=%s: %s", self.model, json.dumps(body)[:300])
            raise LlmError(f"返回结构异常: {json.dumps(body)[:300]}") from None
        _log.debug("大模型调用完成 model=%s 图片=%s 返回 %d 字",
                   self.model, img is not None, len(text or ""))
        return text

    # ---------- 业务封装 ----------
    def choose(self, img: np.ndarray, candidates: List[dict], goal: str = "",
               context: Optional[List[str]] = None) -> dict:
        """先标注后决策：把已编号的 candidates 交给模型，返回它选中的 id。

        candidates 只含可点击项（带 id）；context 是只读上下文（任务追踪/提示/弹窗正文），
        不带 id，只供模型判断。返回 dict：{scene, reason, choose:[合法id], other, done}。坐标不经模型。
        每次调用都把「模型原始输出 + 判断结果」写进项目根的 ai-decisions.log（source=mcpserver）。
        """
        context = list(context or [])
        context_hint = "\n".join(context) or "（无）"
        user = (
            f"目标：{goal or '推进当前任务'}\n\n"
            f"可点击项（只能从这些里选 id，不要发明新文字）：\n"
            f"{_candidates_hint(candidates) or '（无可点击项）'}\n\n"
            f"画面其他信息（只读，用于判断，不要选）：\n"
            f"{context_hint}\n\n"
            "请返回JSON。"
        )
        started = time.time()
        lines = [
            f"{decision_log.stamp()}  source=mcpserver  model={self.model}"
            f"  可点 {len(candidates)} 项  只读 {len(context)} 项",
            f"目标：{decision_log.one_line(goal or '推进当前任务')}",
        ]
        try:
            raw = self._chat(user, img)
        except LlmError as e:
            cost = int((time.time() - started) * 1000)
            _log.warning("大模型选择失败 %dms 候选 %d 项: %s", cost, len(candidates), e)
            lines.append(f"调用失败 {cost}ms：{decision_log.one_line(e)}")
            decision_log.write(lines)
            raise
        cost = int((time.time() - started) * 1000)
        data = _extract_json(raw)
        lines.append(f"模型 {cost}ms  parsed={data is not None}")
        if data is None:
            lines.append(f"原始 {decision_log.one_line(raw, 800)}")
            decision_log.write(lines)
            return {"scene": "other", "reason": f"模型未返回可解析JSON: {raw[:120]}",
                    "choose": [], "done": False, "_raw": raw}
        valid = {c["id"] for c in candidates}
        ids: List[int] = []
        for v in (data.get("choose") or []):
            try:
                iv = int(v)
            except (TypeError, ValueError):
                continue
            if iv in valid and iv not in ids:
                ids.append(iv)
        data["choose"] = ids
        data.setdefault("scene", "other")
        data.setdefault("reason", "")
        data.setdefault("done", False)
        # 判断结论：场景、是否收工、选中项（带回填文字）与理由，足够还原一次决策
        picked = [f"[{c['id']}]{c['text']}" for c in candidates if c["id"] in ids]
        lines.append(f"判断 scene={data['scene']}  done={data['done']}  "
                     f"选 {picked or '无'}  other={decision_log.one_line(data.get('other'))}")
        lines.append(f"理由 {decision_log.one_line(data.get('reason'))}")
        lines.append(f"原始 {decision_log.one_line(raw, 800)}")
        decision_log.write(lines)
        return data

    def advise(self, img: np.ndarray, regions, goal: str) -> dict:
        """只给建议不执行：融合区域 → 可点/只读分流 → choose 选 id → 回写 actions。

        返回集内除 scene/reason/done 外，`clickable` 是带 id 的可点候选，`context` 是
        只读上下文行；actions 里的 click_point 用的是我方区域坐标，模型没参与坐标。
        """
        cands, context = build_candidates(regions, self.clickable_kinds, self.empty_fallback)
        data = self.choose(img, cands, goal, context=context)
        cmap = {c["id"]: c for c in cands}
        acts = [{"action": "click_point", "x": cmap[i]["x"], "y": cmap[i]["y"]}
                for i in data["choose"] if i in cmap]
        other = data.get("other")
        if isinstance(other, dict) and (other.get("action") or "").lower() in ("press", "wait"):
            acts.append(other)
        data["actions"] = acts
        data["clickable"] = cands
        data["context"] = context
        return data

    def decide_dialog(self, img: np.ndarray, regions, hint: str = "") -> Optional[dict]:
        """对话专用（区域分流后选择）：返回我方区域清单中真实存在的选项，供安全点击。"""
        cands, context = build_candidates(regions, self.clickable_kinds, self.empty_fallback)
        try:
            data = self.choose(img, cands, hint or "推进对话/任务", context=context)
        except LlmError:
            return None
        cmap = {c["id"]: c for c in cands}
        for cid in data.get("choose") or []:
            c = cmap.get(cid)
            if c:
                # 用我方区域坐标点击：模板命中中心比「按文字重新 OCR 一次」更稳
                return {"action": "click_point", "x": c["x"], "y": c["y"]}
        return None


# ---------- 动作执行器 ----------
def _norm_to_px(v: float) -> float:
    """模型给的归一化坐标(0~1)容错：<=1.5 视为归一化, 否则当作已给像素(交回原值)。"""
    return v


def execute_actions(ctx, actions: List[dict], img_shape) -> int:
    """执行模型推荐的 actions，返回实际执行的动作数。

    ctx 为 BotContext：click_text 走 OCR 精确命中, click_point 走归一化坐标。
    """
    from ..core import input as user_input  # 延迟导入避免环依赖
    h, w = img_shape[:2]
    executed = 0
    for act in actions or []:
        if user_input.is_stopped():
            break
        kind = (act.get("action") or "").lower()
        if kind == "click_text":
            text = (act.get("text") or "").strip()
            if not text:
                continue
            if ctx.click_text(text, timeout=0):
                executed += 1
        elif kind == "click_point":
            x = float(act.get("x", 0.5))
            y = float(act.get("y", 0.5))
            px = x * w if x <= 1.5 else x
            py = y * h if y <= 1.5 else y
            ctx.input.click(px, py)
            executed += 1
        elif kind == "press":
            key = (act.get("key") or "").strip()
            if key:
                ctx.input.press_key(key)
                executed += 1
        elif kind == "wait":
            import time
            time.sleep(max(0.0, min(float(act.get("sec", 1)), 10.0)))
    return executed


def run_advisor_loop(ctx, goal: str = "", max_steps: int = 120,
                     idle_limit: int = 4, step_pause: Optional[float] = None) -> int:
    """纯「识别 → 大模型决策 → 执行」连续循环（先标注后决策）：
      每轮 snapshot -> ctx.analyze 三层融合出区域 -> build_candidates 分流可点/只读
      -> advisor.choose 只从可点项选 id -> execute_choice 用我方坐标回填点击。
    直到 done / 连续无动作 / 达到 max_steps / 被急停。返回实际轮数。
    节奏参数（step_pause / idle_pause / click_pause）由 config.timing 控制，默认已调快。
    """
    import time
    from ..core import input as user_input
    default_goal = "持续推进并完成当前可做的游戏任务（优先右侧任务追踪的目标）"
    goal = goal or default_goal
    if step_pause is None:
        step_pause = _timing(ctx, "step_pause", 0.4)
    idle_pause = _timing(ctx, "idle_pause", 0.8)
    steps = 0
    idle = 0
    # 绕圈规避：以"画面"为单位记录哪些选项是错的选择（点了会绕回旧画面），
    # 下次回到该画面就推进到下一个没选过的选项——而不是把重复项永久拉黑
    # （一个画面的选项轮完一轮后清空记录、允许回头再点）。
    tried_by_screen: dict = {}     # screen_key(frozenset 文字) -> set(错选文字)
    trail: list = []               # 最近若干轮的 (screen_key, 选中文字tuple)
    TRAIL_MAX = 8

    def _screen_key(rs):
        """画面指纹用全部区域文本（识别画面不能只看按钮），模板区域用模板名补位。"""
        marks = set()
        for r in rs:
            if r.text:
                marks.add(r.text)
            elif (r.meta or {}).get("template"):
                marks.add("@" + str(r.meta["template"]))
        return frozenset(marks)

    while steps < max_steps and not user_input.is_stopped():
        # 人工接管中则在这里等（等不到就整轮结束，绝不带着用户操作状态去点鼠标）
        if not ctx.guard():
            _log.warning("人工接管等待被中断，结束循环")
            break
        # 记下本轮开始时的暂停代次：算完候选后若已变化，说明期间被人接管过，
        # 暂停前的截图与坐标全部作废，必须重新感知一轮。
        gen = ctx.guard_generation()
        steps += 1
        img, results = ctx.snapshot()
        # 三层融合（模板 → YOLO → OCR）后再分流：只有 button 类区域带 id 可点，其余只读
        regions = ctx.analyze(img, results)
        clickable, context = build_candidates(regions, ctx.advisor.clickable_kinds,
                                             ctx.advisor.empty_fallback)
        # 绕圈规避：若本画面是曾经到过的画面(说明绕回来了)，就把"导致绕回来的上一步
        # 选择"在它所属画面内暂时避开，下次该画面改选下一个选项——而不是排除重复项。
        key = _screen_key(regions)
        prev_keys = [k for (k, _) in trail]
        if key in prev_keys and trail:
            last_key, last_texts = trail[-1]
            if last_texts:
                tried_by_screen.setdefault(last_key, set()).update(last_texts)
                _log.info("检测到绕圈：回到已访问画面，暂避上一步选项 %s（下次改选下一个，不永久排除）",
                          list(last_texts))
                del trail[:prev_keys.index(key) + 1]
        tried = tried_by_screen.get(key, set())
        advance = [c for c in clickable if c["text"] not in tried]
        if not advance:
            # 本画面选项已轮完一轮：清空记录允许回头再点（不永久排除重复项）
            tried_by_screen[key] = set()
            tried = set()
            advance = list(clickable)
        task_ctx = ""
        if tried:
            task_ctx = (f"｜注意：本画面这些选项之前选了会绕回来："
                        f"{sorted(tried)}，"
                         "请从剩余没试过的选项里选一个能真正推进任务的不同选项")
        try:
            plan = ctx.advisor.choose(img, advance, goal + task_ctx, context=context)
        except LlmError as e:
            _log.error("大模型调用失败，结束循环: %s", e)
            break
        picked = [cmap_find(advance, i) for i in plan.get("choose") or []]
        picked_txt = [f"[{c['id']}]{c['text']}" for c in picked if c]
        _log.info("大模型#%d scene=%s done=%s :: %s :: 选 %s", steps, plan.get("scene"),
                  plan.get("done"), plan.get("reason"), picked_txt or plan.get("other") or "无")
        if plan.get("done"):
            _log.info("大模型目标已达成，结束循环（%d 轮）", steps)
            break
        if ctx.guard_generation() != gen:
            _log.info("暂停期间画面已变，丢弃本轮候选重新感知")
            continue
        clicked_texts = [c["text"] for c in picked if c]
        executed = execute_choice(ctx, advance, plan, generation=gen)
        if executed:
            idle = 0
            # 记录本轮(画面, 选中项)：下一轮若回到旧画面即可据此定位"该换的下一步"
            trail.append((key, tuple(clicked_texts)))
            if len(trail) > TRAIL_MAX:
                trail.pop(0)
            time.sleep(step_pause)
        else:
            idle += 1
            time.sleep(idle_pause)
            if idle >= idle_limit:
                _log.warning("连续 %d 轮无可执行动作（可能已无事可做/需人工），结束循环", idle)
                break
    return steps


def cmap_find(cands, cid):
    """从 candidates 里按 id 取回对应项。"""
    return next((c for c in cands if c["id"] == cid), None)


def execute_choice(ctx, candidates: List[dict], plan: dict,
                   generation: Optional[int] = None) -> int:
    """按模型选中的 id 回填我方坐标并执行；返回实际执行次数。

    这是“模型只选、坐标由我们定”的关键：不信任模型输出的任何像素。
    generation 不为 None 时，每次点击前比对一次：不一致说明期间被人工接管，
    暂停前算出的坐标已不属于当前画面，立即中止剩余动作。
    """
    import time
    from ..core import input as user_input
    cmap = {c["id"]: c for c in candidates}
    executed = 0
    for cid in plan.get("choose") or []:
        if user_input.is_stopped():
            break
        if generation is not None and ctx.guard_generation() != generation:
            _log.info("点击前检测到画面代次变化（人工接管），中止本轮剩余动作")
            break
        c = cmap.get(cid)
        if not c:
            continue
        ctx.input.click(c["x"], c["y"])
        executed += 1
        time.sleep(_timing(ctx, "click_pause", 0.2))
    other = plan.get("other")
    if isinstance(other, dict):
        a = (other.get("action") or "").lower()
        if a == "press" and other.get("key"):
            ctx.input.press_key(str(other["key"]))
            executed += 1
        elif a == "wait":
            time.sleep(max(0.0, min(float(other.get("sec", 1)), 10.0)))
    return executed
