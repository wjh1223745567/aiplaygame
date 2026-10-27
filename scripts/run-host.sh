#!/usr/bin/env bash
# 构建并运行 host 层（HTTP 工具门面，默认 18080）。
#
#   bash scripts/run-host.sh            # 构建（如需要）并前台启动
#
# 构建产物：host/target/host.jar（由 host/pom.xml 的 finalName 固定）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=env.sh
. "$ROOT/scripts/env.sh"

JAR="$ROOT/host/target/host.jar"

# 用 find 比目录 mtime 更准：改文件内容不会更新父目录时间，-nt 会漏构建。
if [ ! -f "$JAR" ] || [ -n "$(find "$ROOT/host/src" "$ROOT/mcpclient/src" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
  echo "[run-host] 构建 host（含 mcpclient）..."
  ( cd "$ROOT" && mvn -q -DskipTests package )
fi

echo "[run-host] 启动 host（/ai/tools | /ai/invoke | /ai/frame），端口 18080 ..."
# 切到仓库根再启：日志按相对路径写到 logs/，换个目录调用也不会写错地方
cd "$ROOT"
exec java -jar "$JAR" "$@"
