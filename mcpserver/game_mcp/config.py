"""配置加载：游戏侧在 `mcpserver/config.json`，大模型侧在 `host/config.json`。

约定：
- 本文件位于 `mcpserver/game_mcp/config.py`，项目根 = 上两级（含 assets/、debug/）。
  加载后把进程 cwd 切到项目根，使配置里的相对路径
  （assets/templates、debug、assets/models/*.pt）按原样生效。
- 游戏配置：`mcpserver/config.json`（环境变量 AUTOGAME_CONFIG 可覆盖整个文件）
- 大模型配置：`host/config.json` 的 llm 段（环境变量 AUTOGAME_LLM_CONFIG 可覆盖），
  与 host 的 AiSettings 读同一份，密钥只存一处。
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
    """游戏侧配置路径：显式 > 环境变量 AUTOGAME_CONFIG > mcpserver/config.json。"""
    if explicit:
        return os.path.abspath(explicit)
    env = os.environ.get("AUTOGAME_CONFIG")
    if env:
        return os.path.abspath(env)
    return os.path.join(SERVER_DIR, "config.json")


def llm_path() -> str:
    """大模型配置路径：环境变量 AUTOGAME_LLM_CONFIG > host/config.json（与 host 共用）。"""
    env = os.environ.get("AUTOGAME_LLM_CONFIG")
    if env:
        return os.path.abspath(env)
    return os.path.join(PROJECT_ROOT, "host", "config.json")


def load_config(path: Optional[str] = None, reload: bool = False) -> dict:
    """读取并缓存整份 config dict；首次加载时把 cwd 切到项目根。

    游戏配置里没有 llm 段时，从 host/config.json 合并进来，
    这样 `Advisor(cfg)`、`daily`/`market` 等读 cfg["llm"] 的代码不用改。
    """
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
    if not cfg.get("llm"):
        llm_file = llm_path()
        if os.path.exists(llm_file):
            try:
                with open(llm_file, "r", encoding="utf-8") as f:
                    cfg["llm"] = json.load(f).get("llm", {})
            except (OSError, ValueError) as e:  # json 解析失败也不能让 server 起不来
                log.warning("读取 %s 失败，llm 使用默认值: %s", llm_file, e)
        else:
            log.warning("%s 不存在，llm 使用默认值", llm_file)
    _cached = cfg
    log.info("配置加载完成 %s（llm=%s）", cfg_file, "已合并" if cfg.get("llm") else "默认")
    # 让相对路径（assets/debug）相对项目根生效
    try:
        os.chdir(PROJECT_ROOT)
    except OSError:
        pass
    return cfg
