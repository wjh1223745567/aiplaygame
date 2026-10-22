#!/usr/bin/env bash
# 一键准备项目内运行时：JDK 21（Temurin，解压到 .tools）+ MCP Server 的 Python 3.13 虚拟环境。
#
#   bash scripts/bootstrap.sh
#
# 特性：全部落在仓库内、不需要 sudo、可重复执行（已就绪的步骤自动跳过）。
# 说明：本机 macOS 13 已不被 Homebrew 支持（openjdk 无 bottle 会走源码编译），
#       故不走 brew，直接拉 Adoptium 官方 tar.gz 与 python-build-standalone。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JDK_DIR="$ROOT/.tools/jdk-21"
JDK_TAR_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/aarch64/mac/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz"
SERVER_VENV="$ROOT/mcpserver/.venv"
PY_INDEX="https://pypi.tuna.tsinghua.edu.cn/simple"
PY_MIRROR="https://registry.npmmirror.com/-/binary/python-build-standalone/"
UV_VENV="$ROOT/.tools/uv-venv"

log() { printf '\033[1;34m[bootstrap]\033[0m %s\n' "$*"; }

# ---------- 1. JDK ----------
jdk_ready() {
  [ -x "$JDK_DIR/Contents/Home/bin/java" ] || [ -x "$JDK_DIR/bin/java" ]
}

if jdk_ready; then
  log "JDK 已就绪：$JDK_DIR"
else
  mkdir -p "$ROOT/.tools"
  log "下载 Temurin 21（清华镜像）..."
  curl -fL --connect-timeout 20 -o "$ROOT/.tools/jdk21.tar.gz" "$JDK_TAR_URL"
  log "解压到 .tools ..."
  tar -xzf "$ROOT/.tools/jdk21.tar.gz" -C "$ROOT/.tools"
  rm -f "$ROOT/.tools/jdk21.tar.gz"
  extracted="$(find "$ROOT/.tools" -maxdepth 1 -type d -name 'jdk-21*' | head -1)"
  rm -rf "$JDK_DIR"
  mv "$extracted" "$JDK_DIR"
  jdk_ready || { echo "JDK 解压后未找到 java 可执行文件" >&2; exit 1; }
  log "JDK 安装完成：$JDK_DIR"
fi

# ---------- 2. uv（拉预编译 CPython 用，避免本机编译）----------
uv_bin() {
  for c in "$UV_VENV/bin/uv" "$(command -v uv || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}

if ! UV="$(uv_bin)"; then
  log "未发现 uv，创建 .tools/uv-venv 并安装 ..."
  /usr/bin/python3 -m venv "$UV_VENV"
  "$UV_VENV/bin/python" -m pip install -q -U -i "$PY_INDEX" uv
  UV="$UV_VENV/bin/uv"
fi
log "uv: $UV"

# ---------- 3. MCP Server 虚拟环境 ----------
if [ -x "$SERVER_VENV/bin/python" ]; then
  log "mcpserver/.venv 已存在，跳过创建"
else
  log "创建 mcpserver/.venv（CPython 3.13，python-build-standalone 镜像）..."
  UV_PYTHON_INSTALL_MIRROR="$PY_MIRROR" "$UV" venv "$SERVER_VENV" --python 3.13
fi

log "安装 mcpserver/requirements.txt ..."
"$UV" pip install --python "$SERVER_VENV/bin/python" -i "$PY_INDEX" -r "$ROOT/mcpserver/requirements.txt"

log "校验 ..."
"$SERVER_VENV/bin/python" -c "import cv2, mcp, onnxruntime, Quartz; print('mcpserver 依赖 OK')"
"$SERVER_VENV/bin/python" -c "import sys; sys.path.insert(0, '$ROOT/mcpserver'); import game_mcp.server as s; print('tools:', len(s.app._tool_manager._tools))"

# ---------- 4. Java 侧构建（host + mcpclient）----------
if command -v mvn >/dev/null 2>&1; then
  log "构建 host/mcpclient ..."
  ( . "$ROOT/scripts/env.sh"; mvn -q -B -DskipTests -f "$ROOT/pom.xml" package )
  log "构建完成：host/target/host.jar"
else
  log "未发现 mvn，跳过 Java 构建（首次跑 scripts/run-host.sh 时会自动构建）"
fi

log "完成。用前先 source scripts/env.sh"
