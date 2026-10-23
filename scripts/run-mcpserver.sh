#!/usr/bin/env bash
# 以 Streamable HTTP 常驻 mcpserver，供容器里的 host 连接（AUTOGAME_MCP_URL）。
#
# mcpserver 要操作 macOS 本机窗口（pyobjc/Quartz + pynput + mss），
# 所以这里只做「原生常驻」，不起容器；host 才进 Docker。
#
#   bash scripts/run-mcpserver.sh
#   AUTOGAME_MCP_HOST=127.0.0.1 bash scripts/run-mcpserver.sh   # 只允许本机连
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
cd "$ROOT/mcpserver"

export AUTOGAME_MCP_TRANSPORT="${AUTOGAME_MCP_TRANSPORT:-streamable-http}"
export AUTOGAME_MCP_HOST="${AUTOGAME_MCP_HOST:-0.0.0.0}"
export AUTOGAME_MCP_PORT="${AUTOGAME_MCP_PORT:-18090}"
export AUTOGAME_MCP_PATH="${AUTOGAME_MCP_PATH:-/mcp}"

if [ ! -x .venv/bin/python ]; then
  echo "[mcpserver] 缺少 .venv，请先执行 bash scripts/bootstrap.sh" >&2
  exit 1
fi

echo "[mcpserver] transport=$AUTOGAME_MCP_TRANSPORT http://$AUTOGAME_MCP_HOST:$AUTOGAME_MCP_PORT$AUTOGAME_MCP_PATH"
exec .venv/bin/python -m game_mcp.server
