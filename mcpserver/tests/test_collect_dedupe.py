"""失败案例收集的去重回归用例。

同画面的动画帧只存一张、不同画面照存、重启后从 index.jsonl 恢复基线不重存。
画面归属写死而不 glob 生产目录：那边随时会多出新画面（上一次就是被它坑了，
把两个不同画面当成「同一组」去断言）。实测汉明距离：同画面 0-6，不同画面 ≥13。
"""
import glob
import os
import tempfile

import cv2

import support
from game_mcp.config import load_config
from game_mcp.vision import collect as C
from game_mcp.vision.screen import Region

# 同画面：主场景两帧（实测汉明 6）
SAME_SCREEN = ("main_scene.png", "main_scene_alt.png")
# 另外两个独立画面
DIALOG = "dialog_options.png"
TRANSITION = "scene_transition.png"


def _feed(cfg, names):
    """投喂若干帧，返回真正落盘的那些（按文件名后缀）。"""
    saved = []
    for name in names:
        img = cv2.imread(support.frame_path(name))
        r = C.collect_failed_shot(cfg, img, [Region("label", (0, 0, 8, 8), text=name)])
        if r:
            saved.append(os.path.basename(r))
    return saved


def _reset_memory():
    """模拟进程重启：清空进程内基线，逼它从 index.jsonl 恢复。"""
    C._seen.clear()
    C._loaded = False


def test_same_screen_collected_once_and_survives_restart():
    out = tempfile.mkdtemp(prefix="autogame_dedupe_")
    cfg = load_config()
    cfg["capture"] = dict(cfg.get("capture") or {})
    cfg["capture"]["debug_dir"] = out
    try:
        assert len(_feed(cfg, SAME_SCREEN)) == 1, "同画面两帧应只存 1 张"
        assert len(_feed(cfg, (DIALOG,))) == 1, "新画面应存下"
        assert len(_feed(cfg, (TRANSITION,))) == 1, "切场景应存下"

        # 磁盘上就是 3 张：同画面 1 + 对话框 1 + 切场景 1
        n = len(glob.glob(os.path.join(out, "failed_shots", "*.png")))
        assert n == 3, f"磁盘 {n} 张 != 3"

        # 重启后重放全部 4 帧：一张都不该新增
        _reset_memory()
        assert _feed(cfg, SAME_SCREEN + (DIALOG, TRANSITION)) == [], "重启后重放不该新增"
        n = len(glob.glob(os.path.join(out, "failed_shots", "*.png")))
        assert n == 3, f"重启后磁盘 {n} 张 != 3"
    finally:
        _reset_memory()
        import shutil
        shutil.rmtree(out, ignore_errors=True)
