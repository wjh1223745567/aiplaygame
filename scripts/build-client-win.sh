#!/usr/bin/env bash
# 打 Windows x64 便携客户端：client.jar + 内置 JRE 21 + 启动脚本 -> dist/autogame-client-win-x64.zip
#
#   bash scripts/build-client-win.sh                                  # 默认连 192.168.0.31:18080
#   CLIENT_HOST=192.168.0.50 CLIENT_PORT=18080 bash scripts/build-client-win.sh
#
# 产物结构（解压即用，目标机不需要装 Java）：
#   autogame-client-win-x64/
#     start-client.bat   启动器
#     client.jar         客户端（shade 已含 Jackson 等全部依赖）
#     runtime/           Adoptium JRE 21 windows-x64
#     client.properties  预置的服务地址
#     README.txt         使用说明
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
# shellcheck source=env.sh
. "$ROOT/scripts/env.sh"

CLIENT_HOST="${CLIENT_HOST:-192.168.0.31}"
CLIENT_PORT="${CLIENT_PORT:-18080}"

# 清华镜像比 GitHub 稳（GitHub Releases 在国内经常连不上）
JRE_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/x64/windows/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip"
JRE_ZIP="$ROOT/.tools/jre-win-x64.zip"

NAME="autogame-client-win-x64"
DIST="$ROOT/dist/$NAME"
ZIP="$ROOT/dist/$NAME.zip"

echo "[client] 1/4 构建 client.jar"
( cd "$ROOT" && mvn -q -B -DskipTests -pl mcpclient -am package )
JAR="$ROOT/mcpclient/target/client.jar"
[ -f "$JAR" ] || { echo "[client] 缺少 $JAR" >&2; exit 1; }

echo "[client] 2/4 准备 Windows JRE（缓存 ${JRE_ZIP}）"
mkdir -p "$ROOT/.tools"
if [ ! -f "$JRE_ZIP" ]; then
  curl -fL --retry 3 --retry-delay 2 -o "$JRE_ZIP.part" "$JRE_URL"
  mv "$JRE_ZIP.part" "$JRE_ZIP"
fi

echo "[client] 3/4 组装 $DIST"
rm -rf "$DIST" "$ZIP"
mkdir -p "$DIST"

# JRE 解压后有一层 OpenJDK.../ 目录，压平到 runtime/
TMP="$ROOT/dist/.jre-tmp"
rm -rf "$TMP"
unzip -q -o "$JRE_ZIP" -d "$TMP"
INNER="$(find "$TMP" -maxdepth 1 -mindepth 1 -type d | head -1)"
[ -n "$INNER" ] || { echo "[client] JRE 压缩包结构异常" >&2; exit 1; }
mv "$INNER" "$DIST/runtime"
rm -rf "$TMP"

cp "$JAR" "$DIST/client.jar"
cp "$ROOT/mcpclient/packaging/start-client.bat" "$DIST/start-client.bat"
cp "$ROOT/mcpclient/packaging/README.txt" "$DIST/README.txt"
cat > "$DIST/client.properties" <<EOF
# autogame 客户端配置：host/port 指向 host 的 IP 与端口，界面里改完会自动覆盖本文件
host=$CLIENT_HOST
port=$CLIENT_PORT
EOF

[ -f "$DIST/runtime/bin/javaw.exe" ] || { echo "[client] runtime/bin/javaw.exe 缺失，JRE 解压不对" >&2; exit 1; }

echo "[client] 4/4 打包 zip"
( cd "$ROOT/dist" && rm -f "$NAME.zip" && zip -qr "$NAME.zip" "$NAME" )

echo "[client] 完成：$ZIP"
du -sh "$ZIP" "$DIST" | sed 's/^/         /'
echo "[client] 目标机：解压 -> 双击 start-client.bat（默认 ${CLIENT_HOST}:${CLIENT_PORT}）"
