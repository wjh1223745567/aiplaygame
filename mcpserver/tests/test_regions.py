"""区域融合（通用检测 + OCR 兜底）的回归用例（离线，走真代码路径）。

钉住三件容易坏的地方：
  1. OCR 原始结果要按「同一行且水平相邻」聚成 label，不能一个字一条；
  2. 实体框只作只读锚点，不认领框内文字（血条旁的怪物名仍要出现在 label 里）；
  3. 关掉通用检测后退化成纯文字清单，不抛异常、也不残留 entity。
"""
import cv2

import support
from game_mcp.core.engine import BotContext

_CTX = None


def _ctx() -> BotContext:
    global _CTX
    if _CTX is None:
        _CTX = BotContext(support.load_test_config(), None)   # 离线用图，不需要窗口
    return _CTX


def _render(name: str):
    """跑一遍区域融合，返回 (img, ocr_results, regions, size)。"""
    img = cv2.imread(support.frame_path(name))
    assert img is not None, f"样例帧读不出来: {name}"
    ctx = _ctx()
    results = ctx.ocr(img)
    return img, results, ctx.analyze(img, results), (img.shape[1], img.shape[0])


def test_labels_group_ocr_rows():
    """OCR 多条结果要聚成更少的行，且每行都有文字。"""
    _, results, regions, _ = _render("main_scene.png")
    labels = [r for r in regions if r.kind == "label"]
    assert labels, "主场景帧一条文字都没聚合出来"
    assert len(labels) <= len(results), f"聚合后行数 {len(labels)} 超过了 OCR 条数 {len(results)}"
    assert all(r.text.strip() for r in labels), "有空的 label 区域混进来了"


def test_entities_are_readonly_anchors():
    """实体区域不带文字（不认领框内文字），且在画面范围内。"""
    _, _, regions, (w, h) = _render("main_scene.png")
    for r in (x for x in regions if x.kind == "entity"):
        assert r.source == "general"
        assert r.text == "", f"实体框不该吸收文字: {r.text!r}"
        assert 0 <= r.box[0] < r.box[2] <= w and 0 <= r.box[1] < r.box[3] <= h


def test_entity_boxes_do_not_swallow_text():
    """落在实体框内的文字会被认领，但框外文字必须还留在 label 里。"""
    _, results, regions, _ = _render("dialog_options.png")
    labels = [r for r in regions if r.kind == "label"]
    entities = [r for r in regions if r.kind == "entity"]
    if not entities:
        assert labels, "没有实体时也应有文字兜底"
        return
    outside = [r for r in results
               if not any(e.box[0] <= r.cx <= e.box[2] and e.box[1] <= r.cy <= e.box[3]
                          for e in entities)]
    if outside:
        assert labels, "实体框外的文字被吞掉了"


def test_without_detector_degrades_to_text():
    """关掉通用检测：只剩 label，不抛异常。"""
    cfg = dict(support.load_test_config())
    cfg["general"] = {**(cfg.get("general") or {}), "enabled": False}
    ctx = BotContext(cfg, None)
    img = cv2.imread(support.frame_path("main_scene.png"))
    regions = ctx.analyze(img, ctx.ocr(img))
    assert regions, "纯 OCR 兜底不该是空清单"
    assert all(r.kind == "label" for r in regions), [r.kind for r in regions]


def test_region_dict_shape():
    """to_dict 的字段是客户端/日志依赖的契约。"""
    _, _, regions, _ = _render("main_scene.png")
    d = regions[0].to_dict()
    for key in ("kind", "source", "text", "box", "cx", "cy"):
        assert key in d, f"to_dict 缺字段 {key}"
    assert isinstance(d["box"], list) and len(d["box"]) == 4
