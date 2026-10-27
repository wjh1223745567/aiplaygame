"""测试公用：把 mcpserver 放进 sys.path，定位样例帧。

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


def load_test_config() -> dict:
    """读项目配置；识别链路不需要额外开关，原样返回。"""
    from game_mcp.config import load_config
    return load_config()
