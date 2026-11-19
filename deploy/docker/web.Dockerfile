# 不写 # syntax=docker/dockerfile:1：它会让 buildkit 去 Docker Hub 拉 frontend 镜像，
# 国内网络下经常 EOF 直接失败。三份 Dockerfile 都只用内置指令，不需要新版 frontend。
# =====================================================================
# 前端静态产物 + Nginx 入口镜像（WP-12）
# =====================================================================
# admin-web 与 mobile-web 在 builder 里各自 vite build，产物按 §12.2 的
# 子路径放进 nginx 根目录：/admin/ 与 /m/。运行时再挂 nginx 配置与证书。
# =====================================================================
ARG REGISTRY=docker.io

FROM ${REGISTRY}/library/node:20-alpine AS build
WORKDIR /src

# 依赖层先行，源码改动不重装 node_modules
COPY admin-web/package.json admin-web/package-lock.json admin-web/
RUN cd admin-web && npm ci --no-audit --no-fund

COPY mobile-web/package.json mobile-web/package-lock.json mobile-web/
RUN cd mobile-web && npm ci --no-audit --no-fund

COPY admin-web/ admin-web/
COPY mobile-web/ mobile-web/

RUN cd admin-web && npm run build \
 && cd ../mobile-web && npm run build

FROM ${REGISTRY}/library/nginx:1.24-alpine

# 时区与日志软链到 stdout，交给 docker 的 json-file 驱动做轮转
RUN apk add --no-cache tzdata curl \
 && ln -sf /dev/stdout /var/log/nginx/access.log \
 && ln -sf /dev/stderr /var/log/nginx/error.log

COPY --from=build /src/admin-web/dist /usr/share/nginx/html/admin
COPY --from=build /src/mobile-web/dist /usr/share/nginx/html/m

EXPOSE 80 443
