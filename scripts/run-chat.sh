#!/usr/bin/env bash
# 工具调用台：直接调 host 的 /ai/invoke，看每个识别工具返回什么。
#
#   bash scripts/run-host.sh                       # 终端 A：先起 host（18080）
#   bash scripts/run-chat.sh                       # 终端 B：进调用台
#   bash scripts/run-chat.sh --tool=detect --args='{"max_n":20}'   # 单次调用（冒烟）
#
# 构建产物：mcpclient/target/chat.jar（shade 打的带依赖可执行包）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=env.sh
. "$ROOT/scripts/env.sh"

JAR="$ROOT/mcpclient/target/chat.jar"

# 用 find 比目录 mtime 更准：改文件内容不会更新父目录时间，-nt 会漏构建。
if [ ! -f "$JAR" ] || [ -n "$(find "$ROOT/mcpclient/src" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "[run-chat] 构建工具调用台（mcpclient/target/chat.jar）..."
  ( cd "$ROOT" && mvn -q -B -DskipTests -pl mcpclient -am package )
fi

exec java -jar "$JAR" "$@"
