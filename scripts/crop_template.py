#!/usr/bin/env python3
"""从 debug/ 截图裁出模板图，直接丢进 assets/templates/。

模板层是整个视觉流水线里坐标最准的一层（`vision/screen.py` 把它排在 YOLO 与 OCR 之前），
但模板图必须从实机截图裁出来，所以给这个最常用的小工具。

用法：
  # 看看有哪些截图可裁（按修改时间倒序，latest 指最新一张）
  python3 scripts/crop_template.py --list

  # 从最新截图裁一个关闭按钮
  python3 scripts/crop_template.py --box 690,90,715,115 --name close_x

  # 指定截图文件，裁完顺便打印该往 config 里加什么
  python3 scripts/crop_template.py --shot debug/inspect_20250101.png \\
      --box 300,400,420,450 --name confirm --kind button

  # 只预览不写文件
  python3 scripts/crop_template.py --box 690,90,715,115 --name close_x --dry-run

裁完要在 mcpserver/config.json 的 template.items 里加一行，模板才会被扫到：
  "close_x": {"file": "close_x.png", "kind": "button"}

kind 取 button / icon / task / dialog / info：
  · button / icon 是「可点」类型（对应 vision.clickable_kinds），info/task/dialog 只作只读锚点；
  · dialog 只作区域锚点，用来限定 OCR 范围，不会被点。
新增模板只需裁图 + 加一行配置，不用改代码。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SHOT_DIR = PROJECT_ROOT / "debug"
DEFAULT_OUT_DIR = PROJECT_ROOT / "assets" / "templates"
IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
# 与 vision/screen.py 的 _MIN_BOX_SIDE 对齐：太小的模板匹配不稳，只做噪声
MIN_SIDE = 12


def list_shots(shot_dir: Path) -> list[Path]:
    """按修改时间倒序返回可用截图。"""
    if not shot_dir.is_dir():
        return []
    shots = [p for p in shot_dir.iterdir()
             if p.is_file() and p.suffix.lower() in IMAGE_EXTS]
    return sorted(shots, key=lambda p: p.stat().st_mtime, reverse=True)


def resolve_shot(arg: str | None, shot_dir: Path) -> Path | None:
    """`latest`/空 -> 最新截图；否则按路径或 debug/ 下的文件名找。"""
    if arg in (None, "", "latest"):
        shots = list_shots(shot_dir)
        return shots[0] if shots else None
    path = Path(arg).expanduser()
    if path.is_file():
        return path
    candidate = shot_dir / arg
    return candidate if candidate.is_file() else None


def parse_box(text: str) -> tuple[int, int, int, int]:
    """解析 `x1,y1,x2,y2`，并归一化为左上/右下顺序。"""
    parts = [p.strip() for p in text.replace("，", ",").split(",") if p.strip()]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("--box 需要 4 个整数：x1,y1,x2,y2")
    try:
        x1, y1, x2, y2 = (int(float(p)) for p in parts)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"--box 解析失败: {text}") from exc
    return (min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="从 debug/ 截图裁出 UI 模板图")
    parser.add_argument("--list", action="store_true", help="列出可裁的截图后退出")
    parser.add_argument("--shot", default="latest",
                        help="截图路径，或 debug/ 下的文件名（默认 latest=最新一张）")
    parser.add_argument("--box", type=parse_box, help="裁剪框 x1,y1,x2,y2（截图像素坐标）")
    parser.add_argument("--name", help="模板名（同时是文件名，不带扩展名），如 close_x")
    parser.add_argument("--kind", default="button",
                        help="区域类型 button/icon/task/dialog/info（默认 button）")
    parser.add_argument("--shot-dir", default=str(DEFAULT_SHOT_DIR), help="截图目录")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR), help="模板输出目录")
    parser.add_argument("--dry-run", action="store_true", help="只校验不写文件")
    args = parser.parse_args(argv)

    shot_dir = Path(args.shot_dir).expanduser()
    shots = list_shots(shot_dir)
    if args.list:
        if not shots:
            print(f"截图目录为空：{shot_dir}（先用 capture 工具存一张）")
            return 1
        print(f"{shot_dir} 下共 {len(shots)} 张（新的在前）：")
        for p in shots[:20]:
            print(f"  {p.name}  {p.stat().st_size // 1024}KB")
        return 0

    if not args.box or not args.name:
        parser.error("裁剪需要 --box 与 --name（先用 --list 挑截图）")

    shot = resolve_shot(args.shot, shot_dir)
    if shot is None:
        print(f"找不到截图：{args.shot}（目录 {shot_dir}）", file=sys.stderr)
        return 1

    img = cv2.imread(str(shot))
    if img is None:
        print(f"图片读不出来（可能不是图片）：{shot}", file=sys.stderr)
        return 1
    height, width = img.shape[:2]
    x1, y1, x2, y2 = args.box
    if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
        print(f"裁剪框 {list(args.box)} 超出图片范围 {width}x{height}", file=sys.stderr)
        return 1
    if min(x2 - x1, y2 - y1) < MIN_SIDE:
        print(f"警告：裁剪框短边 {min(x2 - x1, y2 - y1)}px < {MIN_SIDE}px，"
              f"这种尺寸的模板匹配容易误命中（建议裁到图标外沿再多留 2~4px）",
              file=sys.stderr)

    crop = img[y1:y2, x1:x2]
    out_dir = Path(args.out_dir).expanduser()
    out_path = out_dir / f"{args.name}.png"
    if args.dry_run:
        print(f"[dry-run] {shot.name} {list(args.box)} -> {out_path} "
              f"({crop.shape[1]}x{crop.shape[0]})")
    else:
        out_dir.mkdir(parents=True, exist_ok=True)
        if not cv2.imwrite(str(out_path), crop):
            print(f"写入失败：{out_path}", file=sys.stderr)
            return 1
        print(f"已保存 {out_path}（{crop.shape[1]}x{crop.shape[0]}，来自 {shot.name}）")

    print("\n在 mcpserver/config.json 的 template.items 里加一行（或改已有项）：")
    print(f'  "{args.name}": {{"file": "{args.name}.png", "kind": "{args.kind}"}}')
    print("\n然后用 layout 工具确认命中：source=template 且 kind=%s" % args.kind)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
