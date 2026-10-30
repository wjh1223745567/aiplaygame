#!/usr/bin/env bash
# 把项目内 JDK 装进当前 shell：source scripts/env.sh
# 不依赖系统 JAVA_HOME（本机默认还是 JDK 8）。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

JDK_HOME="$(ls -d "${ROOT}"/.tools/jdk-*/Contents/Home 2>/dev/null | sort | tail -1)"
if [ -z "${JDK_HOME}" ]; then
  echo "未找到项目内 JDK，先执行：scripts/bootstrap.sh" >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="${JDK_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"
echo "JAVA_HOME=${JAVA_HOME}"
