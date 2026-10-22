#!/usr/bin/env bash
# 对话式调试台：用自然语言驱动 host 的 AI 服务（AI 选工具 -> MCP 执行 -> 复述结果）。
#
#   bash scripts/run-host.sh serve          # 终端 A：先起 AI 服务（18080）
#   bash scripts/run-chat.sh                # 终端 B：进对话
#   bash scripts/run-chat.sh --message="游戏开了吗" --vision=false   # 单轮冒烟
#
# 构建产物：mcpclient/target/chat.jar（shade 打的带依赖可执行包）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=env.sh
. "$ROOT/scripts/env.sh"

JAR="$ROOT/mcpclient/target/chat.jar"

# 用 find 比目录 mtime 更准：改文件内容不会更新父目录时间，-nt 会漏构建。
if [ ! -f "$JAR" ] || [ -n "$(find "$ROOT/mcpclient/src" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "[run-chat] 构建对话调试台（mcpclient/target/chat.jar）..."
  ( cd "$ROOT" && mvn -q -B -DskipTests -pl mcpclient -am package )
fi

exec java -jar "$JAR" "$@"
