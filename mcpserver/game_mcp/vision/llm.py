"""免费大模型（OpenAI 兼容接口）决策助手。

用途：不发截图，把「我方视觉流水线的识别结果」交给大模型——模板匹配 / YOLO 区域分类 /
OCR 四层融合去重后的区域清单（类型、文字、方位、尺寸），让它
  1) 判断当前处于什么界面（主场景/对话/战斗/过场/面板/加载…）
  2) 从可点项里挑接下来点哪个（只选 id，坐标全由我方按区域回填）

只发文本的原因：截图得先缩到 base64 塞进请求（788x619 的 jpeg 就有十几万字符），清单只要一千字；
更重要的是模型只能依据我方识别结果判断，不会「看图猜一个我点不到的坐标」，且纯文本模型也能胜任
（更快、更少被限流）。代价是模型看不到画面，所以方位/尺寸必须写全。

零第三方依赖：仅用标准库 urllib 走 OpenAI `/chat/completions` 协议，
默认对接智谱 GLM-4V-Flash（永久免费）。更换任意兼容服务只需改 config.llm 三项。

输出 schema（模型须返回该 JSON）：
{
  "scene": "main|dialog|battle|cutscene|panel|loading|other",
  "reason": "一句话中文说明",
  "choose": [1, 3],           // 只能取自可点击项的 id，按点击顺序
  "other": {"action": "press|wait|none", "key": "esc", "sec": 2},
  "done": false
}
"""
from __future__ import annotations

import json
import os
import re
import time
import urllib.error
import urllib.request
from typing import List, Optional, Tuple

from ..core import decision_log
from ..core.log import get_logger
from .collect import collect_failed_shot
from .screen import _center_in, frame_hash, screen_key, top_layer_box

_log = get_logger("vision.llm")

# 智谱开放平台 OpenAI 兼容端点（GLM-4V-Flash 永久免费）
DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4"
DEFAULT_MODEL = "glm-4v-flash"

SYSTEM_PROMPT = (
    "你是问道的老玩家兼自动决策器。你不会收到截图，只收到我方「模板匹配 / YOLO 区域分类 / OCR」"
    "四层融合去重后的当前画面识别结果，分成【可点击项】(带编号 id) 与【画面其他信息】(只读) 两部分；"
    "每条都带区域类型、文字、方位(相对整幅游戏窗口的九宫格，如「上中」「右下」)与像素尺寸(宽x高)。"
    "请按「正常游戏策略」从可点击项里选出应点击的 id(可多选、按点击顺序)。"
    "\n【识别结果的边界】看不到图，判断只能依据清单：清单里没有的界面元素就当不存在，不要假设；"
    "嫌疑文字可能被 OCR 截断或串行(如「领 取」「师门任 务」)，按最接近的意图理解；"
    "同一条文字多次出现时按方位区分它们。"
    "\n【候选】只给可点击项编号，你只能从中选 id；画面其他信息单独列出，只作判断依据，不要试图点击；"
    "也不得发明清单以外的文字。坐标由我方按 id 回填，你绝不输出坐标；只输出一个JSON对象，无多余文字或代码围栏。"
    "\n【输出字段】scene(main/dialog/battle/cutscene/panel/loading/other), reason(简短中文), "
    "choose(整数id数组，取自清单；无需点则空数组), "
    "other({\"action\":\"press|wait|none\",\"key\":\"esc|enter|space|tab\",\"sec\":秒}), "
    "done(目标已达成时true)。"
    "\n【正常游戏策略】"
    "\n1) 通用：始终朝「当前任务线索」推进，优先选最贴合目标、推进步数最少的项；同一次不要选多个相互冲突的项。"
    "\n2) 主场景：优先点「任务追踪」里师门任务等目标行(会自动寻路/交互，此类行若出现在可点击项里才可点，否则按其他信息的文字线索判断)；若有「领取/签到/奖励」等可领项先领取；不要打开无关面板。"
    "\n3) 对话：选能推进剧情的选项——优先与任务目标对应的NPC/给予/继续/前进/确定/传送；绝不选放弃/取消/拒绝/不接/离开/下次再说。"
    "\n4) 战斗：优先选「自动/自动战斗」挂机；否则攻击与任务相关的怪/主怪；不要点逃跑/防御/无关技能；战斗结束后清理奖励弹窗。"
    "\n5) 面板/弹窗：领完奖励后关闭(点关闭/确定)；不在无关界面久留。"
    "\n6) 加载/过场：choose给空数组并用 other.wait，或等待画面稳定。"
    "\n7) 反循环：若提示某些选项已反复点击无效，绝不要再选它们，改选其他能真正推进任务的不同选项。"
)


class LlmError(RuntimeError):
    pass


class _RetryableLlmError(LlmError):
    """换个模型就可能成功的失败（限流 / 超时 / 网络 / 服务端错误）。"""


def _model_chain(primary: str, fallbacks) -> List[str]:
    """候选模型：主模型在前，其后是 llm.fallback_models（去重、跳过空值）。

    免费视觉模型会被限流（智谱 429 / code 1305「该模型当前访问量过大」），
    单模型一旦被限，自动决策整条链路就停了，所以按名单降级。
    """
    out: List[str] = []
    for name in [primary, *(fallbacks or [])]:
        name = str(name or "").strip()
        if name and name not in out:
            out.append(name)
    return out


def _strip_reasoning(text: str) -> str:
    """剥掉推理模型的包装。

    glm-4.1v-thinking-flash 返回
    ``<think>…</think><answer><|begin_of_box|>{…}<|end_of_box|></answer>``，
    原样交给 _extract_json 会先碰上思考里夹的花括号。
    """
    s = text or ""
    while True:
        start = s.find("<think")
        if start < 0:
            break
        end = s.find("</think", start)
        if end < 0:
            # 没闭合 = 输出被截断或只吐了思考：思考里也夹着花括号，拿它当决策会点错东西，
            # 宁可当这次失败（上层会降级/报错）
            return s[:start].strip()
        close = s.find(">", end)
        s = s[:start] + s[close + 1 if close >= 0 else end + 7:]
    for tag in ("<answer>", "</answer>", "<|begin_of_box|>", "<|end_of_box|>"):
        s = s.replace(tag, "")
    return s.strip()


def _timing(ctx, key: str, default: float) -> float:
    """从 config.timing 读节奏参数（缺失/非法则用默认值）。"""
    try:
        return float(ctx.cfg.get("timing", {}).get(key, default))
    except Exception:  # noqa: BLE001
        return default


# 区域类型的中文名：只读上下文按「类型: 内容」渲染给模型
_KIND_CN = {"button": "按钮", "icon": "图标", "task": "任务",
            "dialog": "弹窗", "info": "信息", "label": "文本", "entity": "实体"}

# 没有文字时直接丢弃的类型：它们的「信息」就是类别名本身，写进去只是噪声。
# 其余类型（button/icon/dialog/entity/template）即使没字也保留——看不到截图时，
# 一个「弹窗 @右下」就是唯一能说明「右边弹了个框」的线索。
_EMPTY_NOISE_KINDS = {"info", "label", "unknown"}


def _where(region, size: Optional[Tuple[int, int]] = None) -> str:
    """区域的方位速记：``上中(393,13) 34x19``。

    不发截图后，方位是模型区分「顶部导航 / 中央弹窗 / 右下确认」的唯一线索，
    所以每条候选和只读项都要带上；size 是整幅画面尺寸（宽, 高），缺省时只给坐标。
    """
    x, y = region.center
    box = tuple(getattr(region, "box", ()) or ())
    wh = f" {box[2] - box[0]}x{box[3] - box[1]}" if len(box) == 4 else ""
    if not size:
        return f"({x},{y}){wh}"
    w, h = size
    col = "左" if x < w / 3 else ("右" if x > w * 2 / 3 else "中")
    row = "上" if y < h / 3 else ("下" if y > h * 2 / 3 else "中")
    pos = "中央" if (row, col) == ("中", "中") else row + col
    return f"{pos}({x},{y}){wh}"


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


def build_candidates(regions, clickable_kinds=("button", "icon"), sources=("yolo",),
                     max_n: int = 40, size: Optional[Tuple[int, int]] = None,
                     top_box: Optional[Tuple[int, int, int, int]] = None
                     ) -> Tuple[List[dict], List[str]]:
    """把融合后的区域拆成 (可点候选带 id, 只读上下文文本)。坐标只由我方产生。

    候选只收「检测层认出来的控件」：`kind in clickable_kinds`（button / icon）且
    `source in sources`。icon 也算可点：HUD 上的背包/地图/好友这类图标本身就是一个
    可点控件，却只被 UITag 归到 Icon 类；把它当只读信息时，模型根本看不到“这里能点”。
    超大 icon 框在融合层已被当容器降级成 info（见 screen._CONTAINER_AREA_RATIO），不会混进来。
    label 兜底层的文本行不进候选——看得见文字不等于能点，任务追踪里的步骤描述、聊天正文、
    玩家名都长着按钮的样子，「正文里的『确定』二字被点」就是这么来的。
    唯一例外是 source=option：弹窗打开时面板内的「彩色文本行」（对话框选项就是画成彩色、
    说明文字画成白色，实测能分开），由 screen.option_regions 产出，开关 vision.option_by_color。
    也不做「可点为空就把文本提升为候选」的兜底：空就如实报空，让模型返回空 choose
    等下一帧，而不是拿一堆文本蒙一个坐标。
    size 是画面尺寸，用于给每项标方位（不发截图时，方位是模型判断布局的唯一依据）。
    top_box 是「顶层面板」矩形（见 screen.top_layer_box）：弹窗盖了遮罩之后，
    矩形外的元素点不动，即使它们长得再像按钮也只进只读上下文，不进候选。
    """
    kinds = {str(k) for k in (clickable_kinds or ())}
    srcs = {str(s) for s in (sources or ())}
    clickable: List = []
    readonly: List = []
    behind_ids: set = set()
    for r in regions:
        if r.kind in kinds and (not srcs or r.source in srcs):
            clickable.append(r)
        else:
            readonly.append(r)
    if top_box and clickable:
        # 只按中心点判定：面板边缘上的按钮（中心在框内）保留，整块在遮罩下的丢弃
        behind = [r for r in clickable if not _center_in(top_box, r.cx, r.cy)]
        if behind:
            clickable = [r for r in clickable if _center_in(top_box, r.cx, r.cy)]
            readonly += behind
            # 记住是哪些：只读行要改成「按钮（遮罩下·点不动）」，光把它挪进只读区不够——
            # 实测模型照样对着这行想选它，因为类型名还写着「按钮」。
            behind_ids = {id(r) for r in behind}
            _log.info("顶层面板 %s 生效：%d 个按钮在遮罩下点不动，降为只读（%s）",
                      top_box, len(behind),
                      "、".join((r.text or r.kind)[:8] for r in behind[:6]))
    if not clickable:
        # 不刷 info：循环里每帧都会走到这里，真正要看的是 choose 日志里的「候选 0 项」
        _log.debug("可点候选为空：本帧没有 %s 类且来源为 %s 的区域",
                   "/".join(sorted(kinds)) or "-", "/".join(sorted(srcs)) or "任意")
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
                      "kind": r.kind, "source": r.source, "where": _where(r, size)})
        if len(cands) >= max_n:
            break
    context: List[str] = []
    for r in readonly:
        text = (r.text or "").strip()
        if not text:
            # 没有文字的区域：只有在「类别本身就是线索」时才写进去。不发截图时，
            # 「弹窗 @中央」就是唯一能说明「中间弹了个框」的线索；而空的 info/label
            # 写进去只会变成「信息: 信息」这种纯噪声。
            if r.kind in _EMPTY_NOISE_KINDS and not (r.meta or {}).get("template"):
                continue
            text = _region_text(r)
        if text:
            tag = _KIND_CN.get(r.kind, r.kind)
            if id(r) in behind_ids:
                tag += "（遮罩下·点不动）"
            context.append(f"{tag}: {text}  @{_where(r, size)}")
    return cands, context


def _candidates_hint(cands: List[dict]) -> str:
    """生成给模型的编号清单：[id] (类型) 文字  方位(坐标) 尺寸。"""
    return "\n".join(
        f"[{c['id']}] ({_KIND_CN.get(c.get('kind', ''), c.get('kind') or '项')}) "
        f"{c['text']}  @{c.get('where') or ''}"
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
        # 免费模型会被限流，按 model + fallback_models 依次降级；model_used 记实际用上的那个
        self.models: List[str] = _model_chain(self.model, llm.get("fallback_models"))
        self.model_used: str = self.model
        self.api_key: str = llm.get("api_key") or os.environ.get("LLM_API_KEY", "")
        self.timeout: float = float(llm.get("timeout", 30))
        self.temperature: float = float(llm.get("temperature", 0.0))
        # system 模板固定不变：首个请求打全量就够，后续每轮再打只会把日志刷满
        self._logged_system = False

    # ---------- 视觉分流配置（config.vision） ----------
    @property
    def clickable_kinds(self) -> List[str]:
        """哪些区域类型可以进入可点候选（button 带底框的按钮 / icon 图标）。"""
        vision = self.cfg.get("vision", {}) or {}
        return list(vision.get("clickable_kinds") or ["button", "icon"])

    @property
    def clickable_sources(self) -> List[str]:
        """哪些来源的区域算「能点的控件」。默认模板命中 / UITag / 面板内彩色选项。"""
        vision = self.cfg.get("vision", {}) or {}
        return list(vision.get("clickable_sources") or ["template", "yolo", "option"])

    @property
    def gate_by_top_layer(self) -> bool:
        """是否按顶层面板门控候选：弹窗打开时，遮罩下的元素不算可点。"""
        vision = self.cfg.get("vision", {}) or {}
        return bool(vision.get("gate_by_top_layer", True))

    # ---------- 底层调用 ----------
    def _chat(self, user_text: str) -> str:
        if not self.api_key:
            _log.warning("未配置 llm.api_key（或环境变量 LLM_API_KEY），大模型决策不可用")
            raise LlmError("未配置 llm.api_key（或环境变量 LLM_API_KEY）")
        last: Optional[LlmError] = None
        for model in self.models:
            try:
                text = self._ask(model, user_text)
                self.model_used = model
                return _strip_reasoning(text)
            except _RetryableLlmError as e:
                last = e
                _log.warning("模型 %s 调用失败，换下一个: %s", model, e)
        raise last or LlmError("没有可用模型（检查 llm.model / llm.fallback_models）")

    def _ask(self, model: str, user_text: str) -> str:
        payload = {
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_text},
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
            _log.error("大模型 HTTP %d 失败 model=%s: %s", e.code, model, detail)
            message = f"HTTP {e.code} 调用失败: {detail}"
            if e.code in (408, 429, 500, 502, 503, 504):
                raise _RetryableLlmError(message) from e
            raise LlmError(message) from e
        except (urllib.error.URLError, TimeoutError) as e:
            reason = getattr(e, "reason", e)
            _log.error("大模型网络错误 model=%s: %s", model, reason)
            raise _RetryableLlmError(f"网络错误: {reason}") from e
        try:
            text = body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            _log.error("大模型返回结构异常 model=%s: %s", model, json.dumps(body)[:300])
            raise LlmError(f"返回结构异常: {json.dumps(body)[:300]}") from None
        if isinstance(text, list):  # 少数网关把 content 返回成分段数组
            text = "".join(p.get("text", "") for p in text if isinstance(p, dict))
        _log.debug("大模型调用完成 model=%s 返回 %d 字", model, len(text or ""))
        return text or ""

    # ---------- 业务封装 ----------
    def choose(self, candidates: List[dict], goal: str = "",
               context: Optional[List[str]] = None,
               size: Optional[Tuple[int, int]] = None) -> dict:
        """先标注后决策：把已编号的 candidates 交给模型，返回它选中的 id。

        candidates 只含可点击项（带 id）；context 是只读上下文（任务追踪/提示/弹窗正文），
        不带 id，只供模型判断。返回 dict：{scene, reason, choose:[合法id], other, done}。坐标不经模型。
        size 是画面尺寸（宽, 高），只用来在 prompt 头部声明「方位怎么读」。
        每次调用都把「模型原始输出 + 判断结果」写进项目根的 ai-decisions.log（source=mcpserver）。
        """
        context = list(context or [])
        context_hint = "\n".join(context) or "（无）"
        screen = (f"画面 {size[0]}x{size[1]}（每条末尾的方位按九宫格：上中=顶部居中，"
                  f"右下=右下角）\n") if size else ""
        user = (
            f"{screen}"
            f"目标：{goal or '推进当前任务'}\n\n"
            f"可点击项（只能从这些里选 id，不要发明新文字）：\n"
            f"{_candidates_hint(candidates) or '（无可点击项）'}\n\n"
            f"画面其他信息（只读，用于判断，不要选）：\n"
            f"{context_hint}\n\n"
            "请返回JSON。"
        )
        # 发出去的东西必须能事后复盘：prompt 是决策质量的唯一输入，判断跑偏时第一手就看它。
        # user 每轮都变，INFO 完整落盘；system 是固定模板，只在进程内首次打印。
        _log.info("发送给模型（user %d 字 / 候选 %d 项 / 只读 %d 项）:\n%s",
                  len(user), len(candidates), len(context), user)
        if not self._logged_system:
            self._logged_system = True
            _log.info("system prompt（%d 字）:\n%s", len(SYSTEM_PROMPT), SYSTEM_PROMPT)
        started = time.time()
        lines = [
            f"{decision_log.stamp()}  source=mcpserver  model={self.model_used}"
            f"  可点 {len(candidates)} 项  只读 {len(context)} 项",
            f"目标：{decision_log.one_line(goal or '推进当前任务')}",
        ]
        try:
            raw = self._chat(user)
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

    def advise(self, regions, goal: str, size: Optional[Tuple[int, int]] = None,
               img=None) -> dict:
        """只给建议不执行：融合区域 → 可点/只读分流 → choose 选 id → 回写 actions。

        返回集内除 scene/reason/done 外，`clickable` 是带 id 的可点候选，`context` 是
        只读上下文行；actions 里的 click_point 用的是我方区域坐标，模型没参与坐标。
        img 传当前帧则一并做顶层面板门控（不传则不门控）。
        """
        top_box = top_layer_box(img) if img is not None and self.gate_by_top_layer else None
        cands, context = build_candidates(regions, self.clickable_kinds,
                                         self.clickable_sources, size=size,
                                         top_box=top_box)
        data = self.choose(cands, goal, context=context, size=size)
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

# ---------- 单步推进（循环由调用方驱动） ----------
# 以前这里是 run_advisor_loop：一次工具调用内部自己转 120 步，外层模型只拿到一个
# steps 数字，看不到过程也无法纠偏。现在一次调用只走一步，过程全写进返回值，
# 循环交给 host 的轮数上限（maxRounds）驱动。
#
# 绕圈规避本来只在一次调用内有效，改成单步后必须跨调用保留，所以状态提到模块级：
#   tried_by_screen  某画面上哪些选项选了会绕回来
#   trail            最近几步的 (画面, 选中项)，用来定位是哪一步导致绕回来
#   hash / regions   上一帧指纹与识别结果（点了没反应时直接复用，省掉 ~2.6s）
_STEP_STATE: dict = {}
_TRAIL_MAX = 8
# 两步之间隔这么久，就当是另一次任务了：外层循环是秒级的，静默两分钟说明人已走开
_STATE_IDLE_RESET_SEC = 120.0


def step_state(key: str = "default") -> dict:
    """取（或建）某个任务的推进状态。key 用任务名（如 daily_task）。"""
    st = _STEP_STATE.get(key)
    if st is None:
        st = {"goal": "", "tried_by_screen": {}, "trail": [], "steps": 0,
              "hash": None, "regions": None, "ts": 0.0}
        _STEP_STATE[key] = st
    return st


def reset_step_state(key: Optional[str] = None) -> None:
    """清空推进状态：任务完成、目标变了、或调用方要重新开始时用。"""
    if key is None:
        _STEP_STATE.clear()
    else:
        _STEP_STATE.pop(key, None)


def step_once(ctx, goal: str = "", state_key: str = "default") -> dict:
    """推进一步：识别一帧 → 让模型从可点项里选 → 用我方坐标执行。

    返回过程字段（外层模型靠这些判断下一步，不再只看到一个步数）：
      scene / reason      模型对当前界面的判断与理由
      chosen              选中的项（id + 回填文字 + 类型），坐标不经过模型
      executed            实际发出去的动作数
      done                模型是否判定目标已达成
      clickable / context 本帧的可点候选与只读信息（n_clickable 是候选数，列表被截断时看它）
      step                本任务累计走到第几步
      note                没执行时的原因（可点为空 / 窗口不在前台 / 人工接管 / 没选中）
    大模型调用失败返回 ok=false，不往外抛；其余异常照旧上抛给工具层兜底。
    """
    from ..core import input as user_input
    st = step_state(state_key)
    goal = goal or "持续推进并完成当前可做的游戏任务（优先右侧任务追踪的目标）"
    if st["ts"] and time.time() - st["ts"] > _STATE_IDLE_RESET_SEC:
        # 隔了很久才来的下一步：当成新任务，别拿上次的「选这个会绕回来」误导它
        _log.info("距上一步已超过 %.0fs，按新任务重算推进状态", _STATE_IDLE_RESET_SEC)
        reset_step_state(state_key)
        st = step_state(state_key)
    if st["goal"] and st["goal"] != goal:
        # 换目标了：旧的「选这个会绕回来」记录对新目标没有意义，清掉免得误导
        _log.info("目标变了，清空上一目标的推进状态")
        reset_step_state(state_key)
        st = step_state(state_key)
    st["goal"] = goal
    st["ts"] = time.time()

    # 字段顺序是有意的：host 把工具结果转给外层模型时会截到 1600 字符，
    # scene/reason/executed/chosen/note 这些判断用的必须排在候选清单前面，
    # 否则一多就被截掉，外层模型又只能看到一个 ok。
    out: dict = {"ok": True, "goal": goal, "step": st["steps"] + 1,
                 "scene": "", "reason": "", "done": False, "executed": 0,
                 "chosen": [], "note": "", "n_clickable": 0,
                 "clickable": [], "context": []}

    # 人工接管中就在这里等（等不到就本步作废，绝不带着用户操作状态去点鼠标）
    if not ctx.guard():
        out["ok"] = False
        out["note"] = "人工接管等待被中断，本步未做任何动作"
        return out
    # 记下本步开始时的暂停代次：算完候选后若已变化，说明期间被人接管过，坐标作废
    gen = ctx.guard_generation()
    st["steps"] = out["step"]

    img = ctx.grab()
    fhash = frame_hash(img)
    if st["regions"] is not None and fhash == st["hash"]:
        # 画面与上一步完全一致：区域必然一样，直接用上帧结果，省掉整图 OCR + 两次检测
        regions = st["regions"]
        _log.debug("画面与上帧一致，复用上帧识别结果（%d 区域）", len(regions))
    else:
        # 四层融合（模板 → UITag → 通用 → OCR）后再分流：只有 button / icon 类区域带 id 可点
        regions = ctx.analyze(img, ctx.ocr(img))
        st["hash"], st["regions"] = fhash, regions
    size = (img.shape[1], img.shape[0])
    # 弹窗把背景压暗了？那背景里的按钮点不动，不能当候选（见 screen.top_layer_box）
    top_box = top_layer_box(img) if ctx.advisor.gate_by_top_layer else None
    clickable, context = build_candidates(regions, ctx.advisor.clickable_kinds,
                                         ctx.advisor.clickable_sources, size=size,
                                         top_box=top_box)
    out["clickable"], out["context"] = clickable, context
    out["n_clickable"] = len(clickable)

    if not clickable:
        # 可点为空 = 模板与检测层都没认出按钮：正是按钮检测最该留样的失败画面
        collect_failed_shot(ctx.cfg, img, regions, goal)
        out["note"] = "本帧没有可点候选（模板与检测层都没认出按钮），未做任何动作"
        return out

    # 绕圈规避：若本画面是曾经到过的画面(说明绕回来了)，就把"导致绕回来的上一步
    # 选择"在它所属画面内暂时避开，下次该画面改选下一个选项——而不是排除重复项。
    key = screen_key(regions)
    trail = st["trail"]
    prev_keys = [k for (k, _) in trail]
    if key in prev_keys and trail:
        last_key, last_texts = trail[-1]
        if last_texts:
            st["tried_by_screen"].setdefault(last_key, set()).update(last_texts)
            _log.info("检测到绕圈：回到已访问画面，暂避上一步选项 %s（下次改选下一个，不永久排除）",
                      list(last_texts))
            del trail[:prev_keys.index(key) + 1]
    tried = st["tried_by_screen"].get(key, set())
    advance = [c for c in clickable if c["text"] not in tried]
    if not advance:
        # 本画面选项已轮完一轮：清空记录允许回头再点（不永久排除重复项）
        st["tried_by_screen"][key] = set()
        tried = set()
        advance = list(clickable)
    task_ctx = ""
    if tried:
        task_ctx = (f"｜注意：本画面这些选项之前选了会绕回来："
                    f"{sorted(tried)}，"
                     "请从剩余没试过的选项里选一个能真正推进任务的不同选项")
    try:
        plan = ctx.advisor.choose(advance, goal + task_ctx, context=context, size=size)
    except LlmError as e:
        _log.error("大模型调用失败: %s", e)
        out["ok"] = False
        out["note"] = f"大模型调用失败: {e}"
        return out

    out["scene"] = plan.get("scene")
    out["reason"] = plan.get("reason")
    out["done"] = bool(plan.get("done"))
    picked = [c for c in (cmap_find(advance, i) for i in plan.get("choose") or []) if c]
    out["chosen"] = [{"id": c["id"], "text": c["text"], "kind": c.get("kind")}
                     for c in picked]
    out["other"] = plan.get("other")
    _log.info("单步#%d scene=%s done=%s :: %s :: 选 %s", out["step"], out["scene"],
              out["done"], out["reason"],
              [f"[{c['id']}]{c['text']}" for c in picked] or out["other"] or "无")

    if out["done"]:
        _log.info("模型判定目标已达成，清空本任务的推进状态")
        reset_step_state(state_key)
        out["note"] = "模型判定目标已达成"
        return out
    if ctx.guard_generation() != gen:
        out["note"] = "等待期间被人工接管，候选作废，本步未执行（下一步重新感知）"
        return out

    out["executed"] = execute_choice(ctx, advance, plan, generation=gen)
    if out["executed"]:
        # 记录本步(画面, 选中项)：下一步若回到旧画面即可据此定位"该换的下一步"
        if picked:
            trail.append((key, tuple(c["text"] for c in picked)))
            if len(trail) > _TRAIL_MAX:
                trail.pop(0)
        time.sleep(_timing(ctx, "step_pause", 0.4))
    else:
        out["note"] = "模型没选中可点项，或选中的动作没发出去（窗口不在前台/已暂停）"
        time.sleep(_timing(ctx, "idle_pause", 0.8))
    if user_input.is_stopped():
        out["note"] = out["note"] or "已触发急停"
    return out


def run_advisor_loop(ctx, goal: str = "", max_steps: int = 120,
                     idle_limit: int = 4, state_key: str = "cli") -> int:
    """命令行/调试用的循环壳：反复调 step_once，直到 done / 连续无动作 / 步数上限。

    MCP 工具不再走这里（daily_task 已是单步，循环交给 host 的轮数上限），
    保留它只是为了离线调试还能一把跑完。返回累计步数。
    """
    from ..core import input as user_input
    idle = 0
    out: dict = {}
    for _ in range(max(1, max_steps)):
        if user_input.is_stopped():
            break
        out = step_once(ctx, goal, state_key=state_key)
        if out.get("done") or not out.get("ok", True):
            break
        if out.get("executed"):
            idle = 0
        else:
            idle += 1
            if idle >= idle_limit:
                _log.warning("连续 %d 步无可执行动作（可能已无事可做/需人工），结束循环", idle)
                break
    return int(out.get("step") or 0)


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
        if not ctx.input.click(c["x"], c["y"]):
            # 窗口不在前台/已暂停：后面几下同样发不出去，直接收尾，
            # 别把没发出去的动作算成「已执行」回去骗外层模型
            _log.warning("选中项 [%s] %s 未发出（窗口不在前台或已暂停），结束本轮",
                         cid, c["text"])
            break
        executed += 1
        time.sleep(_timing(ctx, "click_pause", 0.2))
    other = plan.get("other")
    if isinstance(other, dict):
        a = (other.get("action") or "").lower()
        if a == "press" and other.get("key"):
            if ctx.input.press_key(str(other["key"])):
                executed += 1
        elif a == "wait":
            time.sleep(max(0.0, min(float(other.get("sec", 1)), 10.0)))
    return executed
