#!/usr/bin/env bash
# =====================================================================
# 生成自签 TLS 证书（开发/内网验收用）
# =====================================================================
# 生产环境请把真实证书放到 deploy/tls/server.crt 与 server.key，再重启 nginx。
# 证书目录不入库（.gitignore 已排除 deploy/tls/*.crt|key）。
# =====================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)/tls"
mkdir -p "$DIR"

if [ -f "$DIR/server.crt" ] && [ -f "$DIR/server.key" ]; then
  echo "[tls] 证书已存在，跳过：$DIR"
  exit 0
fi

HOST="${TLS_HOST:-localhost}"
DAYS="${TLS_DAYS:-825}"

openssl req -x509 -nodes -newkey rsa:2048 -days "$DAYS" \
  -keyout "$DIR/server.key" -out "$DIR/server.crt" \
  -subj "/C=CN/ST=Local/L=Local/O=FaceAttendance/CN=${HOST}" \
  -addext "subjectAltName=DNS:${HOST},DNS:localhost,IP:127.0.0.1" >/dev/null 2>&1

chmod 600 "$DIR/server.key"
echo "[tls] 已生成自签证书：$DIR/server.crt（${DAYS} 天，CN=${HOST}）"
