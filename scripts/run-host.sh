#!/usr/bin/env bash
# 构建并运行 host 层。
#
#   bash scripts/run-host.sh ping          # 一次性命令行任务
#   bash scripts/run-host.sh daily --goal=师门
#   bash scripts/run-host.sh serve         # 常驻 REST（默认 18080）
#
# 构建产物：host/target/host.jar（由 host/pom.xml 的 finalName 固定）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=env.sh
. "$ROOT/scripts/env.sh"

JAR="$ROOT/host/target/host.jar"
ACTION="${1:-ping}"

# 用 find 比目录 mtime 更准：改文件内容不会更新父目录时间，-nt 会漏构建。
if [ ! -f "$JAR" ] || [ -n "$(find "$ROOT/host/src" "$ROOT/mcpclient/src" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "[run-host] 构建 host（含 mcpclient）..."
  ( cd "$ROOT" && mvn -q -DskipTests package )
fi

if [ "$ACTION" = "serve" ]; then
  shift || true
  echo "[run-host] 启动 REST，端口 18080 ..."
  exec java -jar "$JAR" "$@"
fi

shift || true
echo "[run-host] action=$ACTION $*"
exec java -jar "$JAR" "--action=$ACTION" "$@"
