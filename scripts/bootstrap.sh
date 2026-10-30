#!/usr/bin/env bash
# 准备项目内运行时：JDK 25 LTS（Temurin，macOS arm64）。已有则跳过。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_URL="https://mirrors.tuna.tsinghua.edu.cn/Adoptium/25/jdk/aarch64/mac/OpenJDK25U-jdk_aarch64_mac_hotspot_25.0.5_8.tar.gz"

if ls -d "${ROOT}"/.tools/jdk-*/Contents/Home >/dev/null 2>&1; then
  echo "JDK 已就绪：$(ls -d "${ROOT}"/.tools/jdk-*/Contents/Home | tail -1)"
  exit 0
fi

mkdir -p "${ROOT}/.tools"
echo "下载 JDK 25 LTS ..."
curl -fL --connect-timeout 20 -o "${ROOT}/.tools/jdk25.tar.gz" "${JDK_URL}"
tar -xzf "${ROOT}/.tools/jdk25.tar.gz" -C "${ROOT}/.tools"
rm -f "${ROOT}/.tools/jdk25.tar.gz"

JDK_HOME="$(ls -d "${ROOT}"/.tools/jdk-*/Contents/Home | tail -1)"
"${JDK_HOME}/bin/java" -version
echo "JDK 安装完成：${JDK_HOME}"
