# 项目内运行时环境（source 后对当前 shell 生效，不写 ~/.zshrc）
#
#   source scripts/env.sh
#
# 提供：JAVA_HOME（.tools/jdk-21）、三层的目录变量与可执行入口。
# 运行时全部落在仓库内，不动系统全局配置。

AUTOGAME_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
export AUTOGAME_ROOT

# --- JDK：Adoptium Temurin 21，解压在 .tools/jdk-21（macOS 为 Contents/Home 布局）---
if [ -x "$AUTOGAME_ROOT/.tools/jdk-21/Contents/Home/bin/java" ]; then
  export JAVA_HOME="$AUTOGAME_ROOT/.tools/jdk-21/Contents/Home"
elif [ -x "$AUTOGAME_ROOT/.tools/jdk-21/bin/java" ]; then
  export JAVA_HOME="$AUTOGAME_ROOT/.tools/jdk-21"
fi

if [ -n "${JAVA_HOME:-}" ]; then
  case ":$PATH:" in
    *":$JAVA_HOME/bin:"*) ;;
    *) PATH="$JAVA_HOME/bin:$PATH" ;;
  esac
  export PATH
fi

# --- 三层目录 ---
# host(Java 宿主) → mcpclient(Java MCP 客户端) → mcpserver(Python MCP 服务)
export AUTOGAME_MCPSERVER_DIR="$AUTOGAME_ROOT/mcpserver"
export AUTOGAME_MCPSERVER_PY="$AUTOGAME_ROOT/mcpserver/.venv/bin/python"
export AUTOGAME_MCPCLIENT_DIR="$AUTOGAME_ROOT/mcpclient"
export AUTOGAME_HOST_DIR="$AUTOGAME_ROOT/host"
export AUTOGAME_HOST_JAR="$AUTOGAME_ROOT/host/target/host.jar"

# 缺少运行时只提示，不阻断（方便先只看代码的场景）
if [ -z "${JAVA_HOME:-}" ]; then
  echo "[env] 未找到项目内 JDK，请先跑 scripts/bootstrap.sh" >&2
fi
if [ ! -x "$AUTOGAME_MCPSERVER_PY" ]; then
  echo "[env] 未找到 mcpserver/.venv，请先跑 scripts/bootstrap.sh" >&2
fi
