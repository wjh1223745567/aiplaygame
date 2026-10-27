"""视觉门控与候选分流的回归用例（离线，走真代码路径）。

锁住三件踩过坑才做对的事：
  1. 弹窗帧必须检出顶层面板，主场景帧不能检出（误检会把正常候选全砍掉）；
  2. 遮罩下的按钮不进候选，且只读区必须标注「（遮罩下·点不动）」——
     以前只是把它挪进只读区、类型名还写着「按钮」，模型照样对着它选；
  3. 面板内的彩色选项行必须成为可点候选（「师门任务」点不到，师门任务就推不下去）。
"""
import cv2

import support
from game_mcp.core.engine import BotContext
from game_mcp.vision.llm import build_candidates
from game_mcp.vision.screen import _center_in, frame_hash, top_layer_box

_CTX = None


def _ctx() -> BotContext:
    global _CTX
    if _CTX is None:
        _CTX = BotContext(support.load_test_config(), None)   # 离线用图，不需要窗口
    return _CTX


def _render(name: str):
    """跑一遍四层融合，返回 (img, regions, size)。"""
    img = cv2.imread(support.frame_path(name))
    assert img is not None, f"样例帧读不出来: {name}"
    ctx = _ctx()
    return img, ctx.analyze(img, ctx.ocr(img)), (img.shape[1], img.shape[0])


def _candidates(regions, size, top_box):
    ctx = _ctx()
    return build_candidates(regions, ctx.advisor.clickable_kinds,
                            ctx.advisor.clickable_sources, size=size, top_box=top_box)


def test_dialog_frame_has_top_layer():
    """弹窗帧要有顶层面板，否则门控形同不存在。"""
    img, _, _ = _render("dialog_options.png")
    assert top_layer_box(img) is not None


def test_main_scene_has_no_top_layer():
    """主场景不能检出面板：误检会让门控砍掉一半正常候选。"""
    for name in ("main_scene.png", "main_scene_alt.png"):
        img, _, _ = _render(name)
        assert top_layer_box(img) is None, f"{name} 不该检出顶层面板"


def test_masked_buttons_demoted_and_labelled():
    """遮罩下的可点项：不进候选，且只读行带「（遮罩下·点不动）」。"""
    img, regions, size = _render("dialog_options.png")
    box = top_layer_box(img)
    gated, context = _candidates(regions, size, box)
    plain, _ = _candidates(regions, size, None)

    # 候选必须全部落在面板内
    outside = [c for c in gated if not _center_in(box, c["x"], c["y"])]
    assert not outside, f"面板外的元素混进了候选: {[c['text'] for c in outside]}"

    # 不门控时在原面板外的那些，正是被降级的；每一行都要带标注
    veiled = [c for c in plain if not _center_in(box, c["x"], c["y"])]
    assert veiled, "该帧本来就有遮罩下的可点项，用例失效了（换样例帧）"
    marked = [ln for ln in context if "遮罩下·点不动" in ln]
    assert len(marked) == len(veiled), f"降级只读 {len(marked)} 项 != 遮罩下原候选 {len(veiled)} 项"

    # 不能留下光秃秃的「按钮: 」行（模型会照着类型名去选）
    naked = [ln for ln in context if ln.startswith("按钮: ")]
    assert not naked, f"只读区还有未标注的按钮行: {naked}"


def test_tall_icon_box_does_not_swallow_option_rows():
    """UITag 把整个 NPC 面板框成一个 icon 大框时，三条任务行必须仍是三个候选。

    踩过的坑：那块 icon 框面积占屏 34%，刚好掉到「大框当容器」的阈值（曾为 0.35）下面，
    于是把「【师门】师门任务 / 速通师门任务 / 【随机师门】特等」连 NPC 名字合成一个候选，
    模型只能盲选，点了落到「文殊天尊」名字上，任务行永远推不动。
    """
    img, regions, size = _render("npc_dialog.png")
    clickable, _ = _candidates(regions, size, top_layer_box(img))
    texts = [c["text"].replace(" ", "") for c in clickable]

    merged = [t for t in texts if "速通师门任务" in t and "特等" in t]
    assert not merged, f"多行被合成一个候选: {merged}"
    for want in ("【师门】师门任务", "【师门】速通师门任务", "【随机师门】特等"):
        assert want in texts, f"缺少独立候选 {want!r}，实际 {texts}"
    # 大框要么被降级成只读容器，要么压根不算可点
    assert not any(c["kind"] == "icon" for c in clickable), texts


def test_dialog_options_become_candidates():
    """面板内的彩色选项行要能点：这是师门任务能推进的前提。"""
    img, regions, size = _render("dialog_options.png")
    gated, _ = _candidates(regions, size, top_layer_box(img))
    texts = {c["text"] for c in gated}
    for want in ("【师门】师门任务", "【师门】速通师门任务"):
        assert want in texts, f"选项 {want!r} 没成为可点候选，实际候选 {texts}"


def test_gate_is_noop_without_panel():
    """没有顶层面板时，门控不能改变候选。"""
    for name in ("main_scene.png", "main_scene_alt.png"):
        _, regions, size = _render(name)
        on, _ = _candidates(regions, size, top_layer_box(cv2.imread(support.frame_path(name))))
        off, _ = _candidates(regions, size, None)
        assert [c["text"] for c in on] == [c["text"] for c in off]


def test_frame_hash_distinguishes_screens():
    """画面指纹：同图同哈希、异图异哈希（决策循环靠它决定要不要复算）。"""
    a = cv2.imread(support.frame_path("dialog_options.png"))
    b = cv2.imread(support.frame_path("main_scene.png"))
    assert frame_hash(a) == frame_hash(a)
    assert frame_hash(a) != frame_hash(b)
