#!/usr/bin/env bash
# 一把梭检查：Python 语法 + 离线回归用例 + Java 编译。
#
#   bash scripts/check.sh
#
# 用例跑的是真代码路径（区域融合 / 工具入口 / 抓帧端点），只需要
# mcpserver/tests/fixtures 里的固定截图，不需要游戏在跑。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="$ROOT/mcpserver/.venv/bin/python"

# 自己带上项目内 JDK：不 source 的话 mvn 会拿调用者 shell 的 JAVA_HOME，
# 若那是系统 Java 8，switch 表达式会报一堆「非法字符 / 孤立的case」，看起来像代码坏了。
# shellcheck source=/dev/null
. "$ROOT/scripts/env.sh"

if [ ! -x "$PY" ]; then
  echo "[check] 没找到 $PY —— 先跑 bash scripts/bootstrap.sh" >&2
  exit 1
fi

echo "[check] Python 语法"
"$PY" - "$ROOT" <<'PY'
import pathlib, py_compile, sys
root = pathlib.Path(sys.argv[1]) / "mcpserver" / "game_mcp"
bad = 0
for f in sorted(root.rglob("*.py")):
    try:
        py_compile.compile(str(f), doraise=True)
    except py_compile.PyCompileError as e:
        bad += 1
        print(f"  编译失败 {f}: {e}")
print(f"  {len(list(root.rglob('*.py')))} 个文件，失败 {bad}")
sys.exit(1 if bad else 0)
PY

echo "[check] 离线回归用例"
if "$PY" -c "import pytest" >/dev/null 2>&1; then
  "$PY" -m pytest -q "$ROOT/mcpserver/tests"
else
  # 没装 pytest 也能跑：同一批 test_* 函数由 run.py 挨个调用
  "$PY" "$ROOT/mcpserver/tests/run.py"
fi

echo "[check] Java 编译"
( cd "$ROOT" && mvn -q -DskipTests compile )

echo "[check] 全部通过"
