#!/usr/bin/env bash
# 构建/启动 host 容器（mcpserver 仍跑宿主机，见 README「Docker 部署」）。
#
#   bash scripts/docker-host.sh            # 打包 jar + 构建镜像 + 后台启动 + 等健康
#   bash scripts/docker-host.sh build      # 只构建镜像
#   bash scripts/docker-host.sh restart    # 重启容器（换了 jar 或环境变量用这个）
#   bash scripts/docker-host.sh logs       # 跟踪容器日志
#   bash scripts/docker-host.sh status     # 容器状态与健康
#   bash scripts/docker-host.sh down
#   bash scripts/docker-host.sh save-base  # 把基础镜像导出成离线 tar（见下）
#   bash scripts/docker-host.sh prune      # 回收悬空 <none> 镜像 + 给构建缓存封顶
#
# 为什么要这个脚本：Docker Hub 在国内常连不通，而 `docker compose build` 在基础镜像
# 不在本地时会安静地卡在 pull 重试上几十分钟，表现得就像「打包很慢」。这里先把基础
# 镜像确保到本地（本地 → 离线 tar → 三个国内镜像源），再交给 compose；基础镜像就绪时
# 整次构建不到 1 秒（只 COPY 一个 22MB jar）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
cd "$ROOT"

JAR="$ROOT/host/target/host.jar"
BASE_IMAGE="${HOST_BASE_IMAGE:-eclipse-temurin:21-jre}"
BASE_TAR="$ROOT/.tools/docker-base-jre21.tar"
PULL_TIMEOUT="${DOCKER_PULL_TIMEOUT:-240}"
# 与 docker-compose.yml 里的 image 保持一致；构建后旧 ID 变悬空，靠它定位回收
IMAGE_REF="${HOST_IMAGE_REF:-autogame-host:0.1.0}"
# 构建缓存上限（超了才裁，保留最近用到的）；设 0 表示完全不清缓存
CACHE_MAX="${DOCKER_CACHE_MAX:-512MB}"
# 直连 Docker Hub 基本不通，国内源优先；镜像名要带 library/ 前缀
MIRRORS=(
  "docker.1ms.run/library/eclipse-temurin:21-jre"
  "docker.m.daocloud.io/library/eclipse-temurin:21-jre"
  "hub.rat.dev/library/eclipse-temurin:21-jre"
)
ACTION="${1:-up}"

log() { printf '[docker-host] %s\n' "$*"; }

# macOS 自带没有 timeout/gtimeout（只有装了 coreutils 才有）：
# 有就用它卡上限，没有就让 docker 自己超时——否则 `timeout ...` 会直接返回 127，
# 被当成「镜像源不可用」而误跳过所有源。
pull_with_timeout() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "$PULL_TIMEOUT" docker pull "$1"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$PULL_TIMEOUT" docker pull "$1"
  else
    docker pull "$1"
  fi
}

ensure_jar() {
  # find -newer 比目录 mtime 准：改文件内容不会更新父目录时间
  if [ ! -f "$JAR" ] ||
     [ -n "$(find "$ROOT/host/src" "$ROOT/mcpclient/src" -newer "$JAR" -print -quit 2>/dev/null)" ]; then
    log "host.jar 不存在或落后于源码，先打包（mvn package）..."
    ( . "$ROOT/scripts/env.sh" && mvn -q -DskipTests package )
  fi
}

ensure_base_image() {
  if docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
    return 0
  fi
  if [ -f "$BASE_TAR" ]; then
    log "基础镜像不在本地，从离线包恢复：$BASE_TAR"
    loaded="$(docker load -i "$BASE_TAR" | sed -n 's/^Loaded image: //p' | head -1)"
    if [ -n "$loaded" ]; then
      docker tag "$loaded" "$BASE_IMAGE"
      log "基础镜像已恢复"
      return 0
    fi
  fi
  for m in "${MIRRORS[@]}"; do
    log "拉取基础镜像：${m}（上限 ${PULL_TIMEOUT}s）"
    if pull_with_timeout "${m}" >/dev/null 2>&1; then
      docker tag "$m" "$BASE_IMAGE"
      log "基础镜像就绪（源 ${m}）"
      return 0
    fi
    log "该源不可用，换下一个"
  done
  cat >&2 <<'EOF'
[docker-host] 所有镜像源都拉不到基础镜像，任选其一：
  1) 让 Docker Desktop 走代理、能连 Docker Hub，再重跑本脚本
  2) 在能联网的机器上：docker pull eclipse-temurin:21-jre && docker save -o base.tar eclipse-temurin:21-jre
     拷到本机后：docker load -i base.tar
  3) 换可用 registry：HOST_BASE_IMAGE=<registry>/eclipse-temurin:21-jre bash scripts/docker-host.sh
EOF
  return 1
}

short_id() { local s="${1#sha256:}"; printf '%s' "${s:0:12}"; }

gc_superseded_image() {
  # 每次发布：COPY 进去的 host.jar 都是新文件 → 新层 → 新 image ID，tag 跟到新 ID，
  # 旧 ID 就成了 <none> 悬空镜像。攒着不删就是「发布几次 docker images 一堆废镜像」。
  # 只删「被本次构建取代的那一个旧 ID」，不用 docker image prune -a——那会把
  # neo4j/mysql/redis 这些当前没起容器的有 tag 镜像也当垃圾删掉。
  local old="$1" new
  new="$(docker image inspect -f '{{.Id}}' "$IMAGE_REF" 2>/dev/null || true)"
  if [ -z "$old" ] || [ -z "$new" ] || [ "$old" = "$new" ]; then
    return 0        # 没有旧镜像，或构建命中缓存（ID 未变）
  fi
  if ! docker image inspect "$old" >/dev/null 2>&1; then
    return 0        # 已经不在了（被手工 prune 过），不是异常
  fi
  if docker rmi "$old" >/dev/null 2>&1; then
    log "已回收被取代的旧镜像 $(short_id "$old")"
  else
    log "旧镜像 $(short_id "$old") 仍被容器占用，本次跳过（容器重建后即可回收）"
  fi
}

trim_build_cache() {
  # BuildKit 缓存只增不减；本项目的构建不到 1 秒，裁掉陈旧缓存不影响速度。
  # 新版 buildx 这个参数叫 --max-used-space，旧版叫 --keep-storage，两个都试。
  [ "$CACHE_MAX" = "0" ] && return 0
  docker buildx prune -f --max-used-space "$CACHE_MAX" >/dev/null 2>&1 \
    || docker buildx prune -f --keep-storage "$CACHE_MAX" >/dev/null 2>&1 \
    || log "构建缓存清理跳过（buildx 参数不兼容）"
}

warn_mcpserver() {
  if ! lsof -nP -iTCP:18090 -sTCP:LISTEN >/dev/null 2>&1; then
    log "提示：宿主机 18090 没有监听，容器里的 host 会连不到 mcpserver。"
    log "      另开一个终端先跑：bash scripts/run-mcpserver.sh"
  fi
}

case "$ACTION" in
  save-base)
    ensure_base_image
    mkdir -p "$(dirname "$BASE_TAR")"
    docker save -o "$BASE_TAR" "$BASE_IMAGE"
    log "已导出 ${BASE_TAR}（$(du -h "$BASE_TAR" | cut -f1)），之后基础镜像被 prune 也能离线恢复"
    ;;
  build)
    ensure_jar
    ensure_base_image
    prev="$(docker image inspect -f '{{.Id}}' "$IMAGE_REF" 2>/dev/null || true)"
    docker compose build
    gc_superseded_image "$prev"
    trim_build_cache
    ;;
  restart)
    # 镜像是构建产物，光跑 `up` 不会重建——compose 认为镜像没变，容器保持 Running。
    ensure_jar
    docker compose up -d --force-recreate
    log "已按最新 jar 重启容器"
    ;;
  logs)
    docker compose logs -f host
    ;;
  status)
    docker compose ps
    docker inspect -f 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}' \
      autogame-host 2>/dev/null || true
    ;;
  down)
    docker compose down
    ;;
  prune)
    # 只清 <none>（无 tag）镜像：有 tag 的一律不动，被容器占用的删不掉就跳过
    n=0
    kept=0
    for id in $(docker images -f dangling=true -q | sort -u); do
      n=$((n + 1))
      if docker rmi "$id" >/dev/null 2>&1; then
        log "已回收悬空镜像 $(short_id "$id")"
      else
        kept=$((kept + 1))
      fi
    done
    log "悬空镜像 ${n} 个，回收 $((n - kept)) 个，仍被容器占用 ${kept} 个"
    trim_build_cache
    docker system df
    ;;
  up)
    ensure_jar
    ensure_base_image
    warn_mcpserver
    prev="$(docker image inspect -f '{{.Id}}' "$IMAGE_REF" 2>/dev/null || true)"
    docker compose build
    docker compose up -d
    # 先 up 再回收：旧容器还在跑时旧镜像删不掉，重建完就成了纯垃圾
    gc_superseded_image "$prev"
    trim_build_cache
    log "等待容器健康..."
    for _ in $(seq 1 40); do
      st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        autogame-host 2>/dev/null || echo missing)"
      case "$st" in
        healthy)
          log "已就绪：http://localhost:18080（health=healthy）"
          exit 0
          ;;
        unhealthy|exited|dead)
          log "容器状态异常：${st}，查看日志：bash scripts/docker-host.sh logs"
          exit 1
          ;;
      esac
      sleep 2
    done
    log "等待健康超时（80s），当前 ${st}；查看日志：bash scripts/docker-host.sh logs"
    exit 1
    ;;
  *)
    log "未知动作：${ACTION}（可用：up build restart logs status down save-base prune）"
    exit 2
    ;;
esac
