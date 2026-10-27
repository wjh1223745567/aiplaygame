"""配置加载：识别侧配置在 `mcpserver/config.json`。

约定：
- 本文件位于 `mcpserver/game_mcp/config.py`，项目根 = 上两级（含 assets/、debug/）。
  加载后把进程 cwd 切到项目根，使配置里的相对路径
  （debug、assets/models/*.pt）按原样生效。
- 配置：`mcpserver/config.json`（环境变量 AUTOGAME_CONFIG 可覆盖整个文件）
- 只有 window / capture / ocr / general 四段：窗口定位、截图落盘、OCR 引擎、通用检测权重。
"""
from __future__ import annotations

import json
import os
from typing import Optional

from .core.log import get_logger

# mcpserver/game_mcp/config.py -> mcpserver/ 与项目根（上两级）
_HERE = os.path.dirname(os.path.abspath(__file__))
SERVER_DIR = os.path.abspath(os.path.join(_HERE, os.pardir))
PROJECT_ROOT = os.path.abspath(os.path.join(_HERE, os.pardir, os.pardir))

log = get_logger("config")

_cached: Optional[dict] = None


def config_path(explicit: Optional[str] = None) -> str:
    """配置路径：显式 > 环境变量 AUTOGAME_CONFIG > mcpserver/config.json。"""
    if explicit:
        return os.path.abspath(explicit)
    env = os.environ.get("AUTOGAME_CONFIG")
    if env:
        return os.path.abspath(env)
    return os.path.join(SERVER_DIR, "config.json")


def load_config(path: Optional[str] = None, reload: bool = False) -> dict:
    """读取并缓存整份 config dict；首次加载时把 cwd 切到项目根。"""
    global _cached
    if _cached is not None and not reload and path is None:
        return _cached
    cfg_file = config_path(path)
    if not os.path.exists(cfg_file):
        log.error("配置文件不存在: %s", cfg_file)
        raise FileNotFoundError(f"配置文件不存在: {cfg_file}")
    log.debug("读取配置 %s", cfg_file)
    with open(cfg_file, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    _cached = cfg
    log.info("配置加载完成 %s（general=%s ocr=%s）", cfg_file,
             (cfg.get("general") or {}).get("enabled"),
             (cfg.get("ocr") or {}).get("engine"))
    # 让相对路径（assets/debug）相对项目根生效
    try:
        os.chdir(PROJECT_ROOT)
    except OSError:
        pass
    return cfg
