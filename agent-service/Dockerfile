# Agent 服务镜像（§12.1）
# 注意：Streamlit 只是界面壳，业务在 app/graph 与 app/session.py
FROM python:3.12-slim

# 国内构建走阿里云 PyPI 镜像（与 Java 侧 settings.xml 的阿里云 Maven 对称）。
# 用 ARG 不用 ENV：只影响构建期 pip，不留在运行时环境里；需要官方源时
# docker compose build --build-arg PIP_INDEX_URL=https://pypi.org/simple 覆盖。
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} -r requirements.txt

# 顶层包 app 的根是 WORKDIR；streamlit 控制台入口不会把 CWD 放进
# sys.path，必须显式指定（python -m streamlit 之外的第二道保险）。
# ⚠️ 必须放在 pip install 之后：Dockerfile 指令变化会击穿其后所有层缓存，
# 放前面会导致改这一行就触发依赖全量重装（2026-11-19 实测 490s）。
ENV PYTHONPATH=/app

COPY app ./app

EXPOSE 8501

# --server.headless 必须开：容器里没有浏览器，否则 Streamlit 会尝试打开
CMD ["python", "-m", "streamlit", "run", "app/main.py", \
     "--server.port=8501", \
     "--server.address=0.0.0.0", \
     "--server.headless=true", \
     "--server.enableCORS=false", \
     "--server.enableXsrfProtection=false"]
