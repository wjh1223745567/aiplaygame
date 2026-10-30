#!/usr/bin/env bash
# 启动服务（前台）：默认 127.0.0.1:8080
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
source "${ROOT}/scripts/env.sh"
cd "${ROOT}"

PORT="${PORT:-8080}"
exec mvn -q spring-boot:run -Dspring-boot.run.arguments="--server.port=${PORT}" "$@"
