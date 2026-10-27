# host 层容器镜像：只装已构建好的 Spring Boot fat jar（mcpclient 已打进去）。
#
# 构建前先在宿主机打包（必须走项目内 JDK21，否则 mvn 会用系统 Java 8 失败）：
#   bash -c '. scripts/env.sh && mvn -q -DskipTests package'
# 或直接 bash scripts/run-host.sh（发现有源码比 jar 新时会自动构建）。
#
# 镜像里不编译：单阶段镜像更小更快，也避免构建期拉 maven 依赖。
#
# 构建上下文必须是 host/ 而不是仓库根（见 host/.dockerignore）：daemon 得先遍历
# 整个上下文才能算出忽略规则，仓库根 2.7 万个文件单遍历就要 4 分钟；这里只有 29 个。
#
# 基础镜像可用 --build-arg 换国内镜像（Docker Hub 直连经常不通，会卡在 pull 上）：
#   docker build --build-arg BASE_IMAGE=docker.1ms.run/library/eclipse-temurin:21-jre \
#     -f host/Dockerfile host
# 平时用 scripts/docker-host.sh，它会先把基础镜像备到本地再构建。
ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

ENV TZ=Asia/Shanghai \
    AUTOGAME_MCP_URL=http://host.docker.internal:18090/mcp

WORKDIR /app
COPY target/host.jar /opt/autogame/host.jar

EXPOSE 18080

# 常驻 /ai/* 工具门面（tools / invoke / frame）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/opt/autogame/host.jar"]

# 只探 TCP：/ai/tools 会触发 MCP 握手，不适合当存活探针
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/18080"]
