# 不写 # syntax=docker/dockerfile:1：它会让 buildkit 去 Docker Hub 拉 frontend 镜像，
# 国内网络下经常 EOF 直接失败。三份 Dockerfile 都只用内置指令，不需要新版 frontend。
# =====================================================================
# 人脸服务镜像（WP-12）
# =====================================================================
# 模型权重不入库（4 个 ONNX 约 198MB），由 compose 以只读卷挂到 /app/models。
# 用 python:3.11-slim：onnxruntime 的 manylinux wheel 需要 glibc，alpine 装不上。
# =====================================================================
ARG REGISTRY=docker.io

FROM ${REGISTRY}/library/python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    FA_MODEL_DIR=/app/models

WORKDIR /app

# libgomp1：onnxruntime 的 OpenMP 运行时，缺了会 ImportError
# curl：容器健康检查
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgomp1 curl tzdata \
 && rm -rf /var/lib/apt/lists/*

COPY face-service-python/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY face-service-python/app ./app

EXPOSE 8000
# workers 数由 compose 注入（FACE_WORKERS）；挑战状态在 Redis，跨进程安全（B3 已验证）
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --workers ${FACE_WORKERS:-1}"]
