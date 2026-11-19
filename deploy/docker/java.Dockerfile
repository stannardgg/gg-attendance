# 不写 # syntax=docker/dockerfile:1：它会让 buildkit 去 Docker Hub 拉 frontend 镜像，
# 国内网络下经常 EOF 直接失败。三份 Dockerfile 都只用内置指令，不需要新版 frontend。
# =====================================================================
# Java 后端镜像（WP-12）
# =====================================================================
# 构建上下文是**仓库根**，因为 fa-bootstrap 会把 ../../db/migration 复制进
# classpath（DDL 只有一处）；只拷 backend-java 会导致 jar 里没有迁移脚本。
#
# 多阶段：builder 里跑 mvn package，运行镜像只留 JRE + jar。
# REGISTRY 可换国内镜像源（见 deploy/.env.example 的 DOCKER_REGISTRY）。
# =====================================================================
ARG REGISTRY=docker.io

FROM ${REGISTRY}/library/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先拷 POM，让依赖下载层可缓存（源码改动不会击穿这一层）
COPY backend-java/pom.xml backend-java/
COPY backend-java/fa-common/pom.xml backend-java/fa-common/
COPY backend-java/fa-infra/pom.xml backend-java/fa-infra/
COPY backend-java/fa-org/pom.xml backend-java/fa-org/
COPY backend-java/fa-auth/pom.xml backend-java/fa-auth/
COPY backend-java/fa-checkin/pom.xml backend-java/fa-checkin/
COPY backend-java/fa-agent/pom.xml backend-java/fa-agent/
COPY backend-java/fa-system/pom.xml backend-java/fa-system/
COPY backend-java/fa-bootstrap/pom.xml backend-java/fa-bootstrap/
RUN mvn -B -ntp -f backend-java/pom.xml -DskipTests dependency:go-offline || true

COPY backend-java/ backend-java/
COPY db/ db/
RUN mvn -B -ntp -f backend-java/pom.xml -DskipTests package

FROM ${REGISTRY}/library/eclipse-temurin:17-jre-jammy
WORKDIR /app

# curl 只用于容器健康检查（compose healthcheck），镜像体积代价可接受
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl tzdata \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/backend-java/fa-bootstrap/target/fa-bootstrap-*.jar /app/app.jar

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

EXPOSE 8080
# exec 保证 java 是 PID 1，docker stop 能收到 SIGTERM（application.yml 已开 graceful shutdown）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
