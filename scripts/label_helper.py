#!/usr/bin/env python3
"""失败案例预标注：把 debug/failed_shots/ 转成 YOLO 训练集的初始标注。

数据攒够（100+ 张）时跑。离线执行：用 `BotContext(cfg, None)` 直接对存档 PNG
重跑四层融合，不需要游戏窗口。按区域来源分流：

  · kind=button 且 source=template 的区域 → 直接写 YOLO label（模板命中是最高置信来源）
  · 其余区域（label/info/dialog/entity…）→ 汇总进 proposals.json 待筛（含文字、方位、尺寸）

proposals 必须「人工过一遍」才能变成 label：OCR 框里既有真按钮，也有聊天正文、
玩家名、任务步骤描述——看得见字不等于能点。筛选两步：
  1. AI 逐张看图核对 proposals.json：删掉明显不是按钮的框，补上图标类按钮
  2. 人用 X-AnyLabeling 打开 datasets/ui/images 抽查修正（建议至少抽 20 张再开训）

用法：
  python3 scripts/label_helper.py                       # 读 <debug_dir>/failed_shots
  python3 scripts/label_helper.py --shots DIR --out DIR # 换目录（试验/验证用）

产物结构：
  datasets/ui/
  ├── images/          # 从 failed_shots 复制
  ├── labels/          # YOLO txt（class cx cy w h 归一化），初期只含模板框
  ├── proposals.json   # 待筛候选（kind/source/text/box/where）
  └── data.yaml        # nc=1, names=['button']（训练脚本会再切成 80/20 的 train.txt/val.txt）
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import cv2

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT_DIR = PROJECT_ROOT / "datasets" / "ui"
sys.path.insert(0, str(PROJECT_ROOT / "mcpserver"))

from game_mcp.config import load_config  # noqa: E402
from game_mcp.core.engine import BotContext  # noqa: E402
# 复用 llm.py 的方位速记（九宫格+坐标+尺寸），免得 proposals 与 prompt 两处格式漂移
from game_mcp.vision.llm import _where  # noqa: E402


def yolo_line(box, width: int, height: int) -> str:
    """区域框 → YOLO 归一化标签行 `0 cx cy w h`（单类 button，class 恒为 0）。"""
    x1, y1, x2, y2 = box
    return (f"0 {(x1 + x2) / 2 / width:.6f} {(y1 + y2) / 2 / height:.6f} "
            f"{(x2 - x1) / width:.6f} {(y2 - y1) / height:.6f}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="失败案例预标注 → datasets/ui")
    parser.add_argument("--shots", default=None,
                        help="失败案例目录（默认 <debug_dir>/failed_shots）")
    parser.add_argument("--out", default=str(DEFAULT_OUT_DIR),
                        help="输出目录（默认 datasets/ui）")
    args = parser.parse_args(argv)

    cfg = load_config()
    debug_dir = cfg.get("capture", {}).get("debug_dir", "debug")
    shots_dir = (Path(args.shots).expanduser() if args.shots
                 else PROJECT_ROOT / debug_dir / "failed_shots")
    # resolve：data.yaml 里的 path 必须是绝对路径，训练脚本才能在任意 cwd 下跑
    out_dir = Path(args.out).expanduser().resolve()

    imgs = sorted(shots_dir.glob("*.png")) if shots_dir.is_dir() else []
    print(f"失败案例: {shots_dir} -> {len(imgs)} 张")
    if not imgs:
        print("  没有可标注的画面：先用 daily_task 攒量"
              "（收集开关 vision.collect_failed_shots，默认开）")

    # 离线上下文：不抓窗口不点击，只复用 模板/YOLO/OCR 三层识别
    ctx = BotContext(cfg, None)
    images_dir = out_dir / "images"
    labels_dir = out_dir / "labels"
    images_dir.mkdir(parents=True, exist_ok=True)
    labels_dir.mkdir(parents=True, exist_ok=True)

    proposals = []
    n_tpl = 0
    for i, p in enumerate(imgs, 1):
        img = cv2.imread(str(p))
        if img is None:
            print(f"  [{i}/{len(imgs)}] {p.name} 读不出来，跳过")
            continue
        h, w = img.shape[:2]
        regions = ctx.analyze(img, ctx.ocr(img))
        lines = []
        props = []
        for r in regions:
            if r.kind == "button" and r.source == "template":
                lines.append(yolo_line(r.box, w, h))
                n_tpl += 1
            else:
                props.append({"kind": r.kind, "source": r.source, "text": r.text,
                              "box": list(r.box), "where": _where(r, (w, h))})
        proposals.append({"image": p.name, "size": [w, h], "proposals": props})
        shutil.copy2(p, images_dir / p.name)
        # 无模板框的图写空 txt（YOLO 里等价于「背景图」，是合法的负样本）
        (labels_dir / f"{p.stem}.txt").write_text(
            "".join(line + "\n" for line in lines), encoding="utf-8")
        print(f"  [{i}/{len(imgs)}] {p.name}: 区域 {len(regions)} / "
              f"模板框 {len(lines)} / 待筛 {len(props)}")

    (out_dir / "proposals.json").write_text(
        json.dumps(proposals, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out_dir / "data.yaml").write_text(
        "# 按钮检测训练集（scripts/label_helper.py 生成）\n"
        f"path: {out_dir}\n"
        "train: images\n"
        "val: images\n"
        "nc: 1\n"
        "names: ['button']\n", encoding="utf-8")

    n_prop = sum(len(x["proposals"]) for x in proposals)
    print(f"\n完成：{len(proposals)} 张图 / 模板框 {n_tpl} 个 / 待筛候选 {n_prop} 个 → {out_dir}")
    print("下一步：筛 proposals.json（删聊天正文/玩家名/步骤描述，补图标按钮），"
          "再用 X-AnyLabeling 打开 images/ 抽查修正")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())