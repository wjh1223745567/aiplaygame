#!/usr/bin/env python3
"""在失败案例标注上微调按钮检测权重（单类 button）。

数据来自 scripts/label_helper.py 预标注 + 人工/AI 筛选后的 datasets/ui。本脚本负责：
  1. 80/20 切分训练/验证（写成 train.txt / val.txt，data.yaml 改指向它们）
  2. 训练（默认 COCO 预训练 yolo11n，CPU 约 2~3 小时；yolo11s 慢约 3 倍）
  3. 训练完自动 val，打印 button 召回（达标线 > 80%）
  4. best.pt 拷到 assets/models/yolo-game-ui.pt（接入 = config 里 yolo.model_path 改一行）

用法：
  python3 scripts/train_button_yolo.py                 # yolo11n / 100 epochs / CPU
  python3 scripts/train_button_yolo.py --model assets/models/yolo11s.pt --epochs 150
  python3 scripts/train_button_yolo.py --device mps    # 环境支持加速器时

注意：本机 MPS 不可用（torch 2.11 + macOS 13），用 CPU 后台跑（独立终端 + nohup）。
产物在 datasets/runs/ 下（已 gitignore）；不达标先别接入，补样本重训。
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DATA = PROJECT_ROOT / "datasets" / "ui" / "data.yaml"
DEFAULT_WEIGHTS_OUT = PROJECT_ROOT / "assets" / "models" / "yolo-game-ui.pt"
IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp")
RECALL_TARGET = 0.80     # 达标线（计划：button 召回 > 80%）


def split_dataset(data_yaml: Path) -> tuple[Path, int, int]:
    """80/20 切分并改写 data.yaml；返回 (images 目录, 训练张数, 验证张数)。"""
    import yaml  # ultralytics 的依赖里自带

    info = yaml.safe_load(data_yaml.read_text(encoding="utf-8")) or {}
    root = Path(str(info.get("path") or data_yaml.parent))
    if not root.is_absolute():
        root = (data_yaml.parent / root).resolve()
    images_dir = root / "images"
    imgs = (sorted(p for p in images_dir.iterdir()
                   if p.is_file() and p.suffix.lower() in IMAGE_EXTS)
            if images_dir.is_dir() else [])
    if len(imgs) < 10:
        raise SystemExit(f"训练集只有 {len(imgs)} 张（{images_dir}）：先用 daily_task 攒量、"
                         f"跑 label_helper 并筛完，少于 10 张连切分都不够")
    # 每 5 张抽 1 张做验证（20%）。确定性切分：重跑不换验证集，指标可比
    train = [p for i, p in enumerate(imgs) if i % 5 != 0]
    val = [p for i, p in enumerate(imgs) if i % 5 == 0]
    rel = lambda p: str(p.relative_to(root))  # noqa: E731
    (root / "train.txt").write_text("".join(rel(p) + "\n" for p in train), encoding="utf-8")
    (root / "val.txt").write_text("".join(rel(p) + "\n" for p in val), encoding="utf-8")
    names = info.get("names", ["button"])
    if isinstance(names, dict):
        names_txt = "{" + ", ".join(f"{k}: {v!r}" for k, v in names.items()) + "}"
    else:
        names_txt = "[" + ", ".join(repr(n) for n in names) + "]"
    data_yaml.write_text(
        "# 由 scripts/train_button_yolo.py 改写（在 label_helper 骨架上加 80/20 切分）\n"
        f"path: {root}\n"
        "train: train.txt\n"
        "val: val.txt\n"
        f"nc: {int(info.get('nc', 1))}\n"
        f"names: {names_txt}\n", encoding="utf-8")
    return images_dir, len(train), len(val)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="按钮检测微调（单类 button）")
    parser.add_argument("--data", default=str(DEFAULT_DATA), help="data.yaml 路径")
    parser.add_argument("--model", default="yolo11n.pt",
                        help="起点权重：默认 COCO 预训练 yolo11n.pt（自动下载约 5MB），"
                             "本地后备 assets/models/yolo11s.pt")
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=8)
    parser.add_argument("--device", default="cpu",
                        help="cpu / mps / 0（本机 MPS 不可用，默认 cpu）")
    parser.add_argument("--name", default="train", help="datasets/runs 下的实验名")
    parser.add_argument("--weights-out", default=str(DEFAULT_WEIGHTS_OUT),
                        help="best.pt 拷贝目标（默认 assets/models/yolo-game-ui.pt）")
    args = parser.parse_args(argv)

    data_yaml = Path(args.data).expanduser().resolve()
    if not data_yaml.is_file():
        print(f"缺 {data_yaml}：先跑 scripts/label_helper.py 生成数据集骨架", file=sys.stderr)
        return 1

    images_dir, n_train, n_val = split_dataset(data_yaml)
    print(f"数据集: {images_dir} -> 训练 {n_train} / 验证 {n_val}（80/20，每 5 张抽 1）")
    if n_train < 80:
        print(f"提示：训练集 {n_train} 张偏少（计划目标 100+），先验证管线可以，召回会更低")

    from ultralytics import YOLO
    model = YOLO(args.model)
    print(f"开始训练：{args.model} / {args.epochs} epochs / imgsz={args.imgsz} / "
          f"batch={args.batch} / device={args.device}")
    model.train(data=str(data_yaml), epochs=args.epochs, imgsz=args.imgsz,
                batch=args.batch, device=args.device,
                project=str(PROJECT_ROOT / "datasets" / "runs"),
                name=args.name, exist_ok=True)

    save_dir = Path(getattr(model.trainer, "save_dir",
                            PROJECT_ROOT / "datasets" / "runs" / args.name))
    best = save_dir / "weights" / "best.pt"
    if not best.is_file():
        raise SystemExit(f"训练结束但找不到 best.pt：{best}")

    # 出指标：达标线 button 召回 > 80%；主页面误报数要真机 layout 复核，本脚本给不出
    metrics = YOLO(str(best)).val(data=str(data_yaml), imgsz=args.imgsz,
                                  device=args.device, verbose=False)
    recall = float(metrics.results_dict.get("metrics/recall(B)", 0.0))
    verdict = "✔ 达标" if recall > RECALL_TARGET else "✘ 未达标：补样本或加 epochs 重训"
    print(f"\n验证集 button 召回 {recall:.1%}（达标线 > {RECALL_TARGET:.0%}）{verdict}")

    out = Path(args.weights_out).expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(best, out)
    rel_out = out.relative_to(PROJECT_ROOT) if out.is_relative_to(PROJECT_ROOT) else out
    print(f"已拷贝到 {out}")
    print(f"\n接入：mcpserver/config.json 的 yolo.model_path 改成 {rel_out}，重启 mcpserver；"
          "\n回退：改回 assets/models/yolo-ui.pt。改完用 layout 工具看 source=yolo "
          "kind=button 的数量是否上升。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())