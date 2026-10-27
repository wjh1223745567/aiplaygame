"""测试公用：把 mcpserver 放进 sys.path，定位样例帧，并关掉真实鼠标监听。

样例帧优先读 tests/fixtures/（入库，换机器也能跑）；本地开发时若还没拷进去，
回落到 debug/ 下的原始截图，免得为了跑一次用例先手工搬文件。
"""
from pathlib import Path
import sys

TESTS = Path(__file__).resolve().parent
MCPSERVER = TESTS.parent          # mcpserver/
REPO = MCPSERVER.parent           # 仓库根
if str(MCPSERVER) not in sys.path:
    sys.path.insert(0, str(MCPSERVER))

FIXTURES = TESTS / "fixtures"

# fixtures/ 里的名字 → debug/ 里的原始文件（仅作本地回落，CI 用 fixtures）
_FALLBACK = {
    "dialog_options.png": "mainscreen_173448.png",
    "main_scene.png": "mainscreen_171649.png",
    "main_scene_alt.png": "mainscreen_162055.png",
    "scene_transition.png": "failed_shots/20261026_180026_ff3f1f3e.png",
}


def frame_path(name: str) -> str:
    """样例帧的绝对路径。"""
    p = FIXTURES / name
    if p.exists():
        return str(p)
    alt = REPO / "debug" / _FALLBACK.get(name, name)
    if alt.exists():
        return str(alt)
    raise FileNotFoundError(
        f"缺少样例帧 {name}：把 debug/ 下的 {_FALLBACK.get(name, name)} "
        f"拷到 {FIXTURES} 再跑（该目录入库，别放运行时产物）")


def quiet_guard() -> None:
    """关掉人工接管保护：用例进程不该挂真实鼠标监听。

    BotContext 构造时会 get_guard().start() 真的装一个全局鼠标监听：跑用例时人一动鼠标，
    工具入口就判定「人工接管」并阻塞到 30s 上限，用例会随机挂（表现为工具有时被跳过）。
    这里只关心代码路径，不需要真监听。
    """
    from game_mcp.core.human_guard import get_guard
    guard = get_guard({"enabled": False})
    guard.enabled = False
    guard.stop()


quiet_guard()   # import 时就生效：每个用例文件都会先 import support


def load_test_config() -> dict:
    """读项目配置，但把人工接管关掉。

    get_guard(cfg) 每次都会 configure，所以光靠 quiet_guard() 不够：只要用例再造一个
    BotContext，监听就又被装回去了。造 ctx 时统一用这份配置。
    """
    from game_mcp.config import load_config
    cfg = load_config()
    cfg["human_guard"] = {**(cfg.get("human_guard") or {}), "enabled": False}
    return cfg
