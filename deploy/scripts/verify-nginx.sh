#!/usr/bin/env bash
# =====================================================================
# nginx 层验收（WP-12 Gate 证据）
# =====================================================================
# 逐条验证 §12.2 的要求：HTTP 跳转、HSTS、gzip、WebSocket 升级、
# 反代超时、TLS 版本、单点入口（其余服务不映射宿主机端口）。
#
# 用法：./scripts/verify-nginx.sh
# 退出码 0 = 全部通过；任何一条失败都会打印期望与实际。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; [ -f .env ] && . ./.env; set +a

HTTP="http://127.0.0.1:${HTTP_PORT:-80}"
HTTPS="https://127.0.0.1:${HTTPS_PORT:-443}"
CURL=(curl -sS -k --max-time 10)
fail=0
ok()  { printf '  [OK]   %s\n' "$*"; }
bad() { printf '  [FAIL] %s\n' "$*"; fail=1; }

echo "=== 1. 单点入口：只有 nginx 映射宿主机端口 ==="
ports=$(docker compose ps --format '{{.Service}} {{.Ports}}' | grep -v '^nginx ' | grep -cE '0\.0\.0\.0:[0-9]+->|:[0-9]+->' || true)
if [ "$ports" -eq 0 ]; then ok "java/face/agent/mysql/redis 均未映射宿主机端口"; else
  bad "有非 nginx 服务映射了端口："; docker compose ps --format '{{.Service}} {{.Ports}}' | grep -v '^nginx '
fi

echo ""
echo "=== 2. HTTP → HTTPS ==="
code=$("${CURL[@]}" -o /dev/null -w '%{http_code}' "$HTTP/api/v1/auth/captcha" || true)
loc=$("${CURL[@]}" -o /dev/null -w '%{redirect_url}' "$HTTP/api/v1/auth/captcha" || true)
if [ "$code" = "301" ] && [[ "$loc" == https://* ]]; then ok "301 → $loc"; else bad "期望 301 到 https，实际 $code / $loc"; fi
hz=$("${CURL[@]}" "$HTTP/healthz" || true)
[ "$hz" = "ok" ] && ok "80 端口保留 /healthz" || bad "80 /healthz 期望 ok，实际 $hz"

echo ""
echo "=== 3. 安全响应头 ==="
h=$("${CURL[@]}" -D - -o /dev/null "$HTTPS/admin/" || true)
for kv in "strict-transport-security: max-age=31536000" "x-content-type-options: nosniff" "referrer-policy: same-origin"; do
  if printf '%s' "$h" | tr 'A-Z' 'a-z' | grep -q "$kv"; then ok "$kv"; else bad "缺少 $kv"; fi
done
# Agent 必须去掉 X-Frame-Options，否则 iframe 嵌入会被浏览器拦掉
ah=$("${CURL[@]}" -D - -o /dev/null "$HTTPS/agent/" || true)
if printf '%s' "$ah" | tr 'A-Z' 'a-z' | grep -q "x-frame-options"; then bad "/agent/ 仍带 X-Frame-Options"; else ok "/agent/ 已去 X-Frame-Options"; fi

echo ""
echo "=== 4. gzip ==="
gz=$("${CURL[@]}" -H 'Accept-Encoding: gzip' -D - -o /dev/null "$HTTPS/admin/" || true)
if printf '%s' "$gz" | tr 'A-Z' 'a-z' | grep -q "content-encoding: gzip"; then ok "text/html 压缩生效"; else bad "admin 首页未压缩"; fi

echo ""
echo "=== 5. TLS 版本 ==="
if command -v openssl >/dev/null 2>&1; then
  if echo | openssl s_client -connect "127.0.0.1:${HTTPS_PORT:-443}" -tls1_2 2>/dev/null | grep -q "Cipher is"; then
    ok "TLS 1.2 可用"
  else bad "TLS 1.2 握手失败"; fi
  if echo | openssl s_client -connect "127.0.0.1:${HTTPS_PORT:-443}" -tls1_1 2>/dev/null | grep -q "Cipher is"; then
    bad "TLS 1.1 仍被接受（应关闭）"
  else ok "TLS 1.1 已拒绝"; fi
else
  echo "  [SKIP] 无 openssl"
fi

echo ""
echo "=== 6. WebSocket 升级（经 nginx 透传）==="
tok=$("${CURL[@]}" -X POST "$HTTPS/api/v1/auth/login" -H 'Content-Type: application/json' \
      -d "{\"username\":\"${BOOTSTRAP_ADMIN_USERNAME:-admin}\",\"password\":\"${BOOTSTRAP_ADMIN_PASSWORD}\"}" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["accessToken"])' 2>/dev/null || true)
if [ -z "$tok" ]; then
  bad "登录失败，无法验证 WS"
else
  # 用标准库 socket 发真正的 HTTP/1.1 握手（curl 默认 h2 会丢 Upgrade 头，测不了 WS）。
  # Java 侧语义：握手先放行 101，鉴权在连接建立后做——
  #   有效 token → 101 且首帧 EVT_CONNECTED；
  #   坏 token   → 101 后服务端主动断开（CloseStatus POLICY_VIOLATION=1008）。
  ws_out=$(python3 - "$tok" "$HTTPS" <<'PY' 2>&1 || true
import socket, ssl, sys
from urllib.parse import urlparse

token, base = sys.argv[1], sys.argv[2]
u = urlparse(base)
host, port = u.hostname, u.port or 443

def handshake(sid, tok):
    raw = socket.create_connection((host, port), timeout=5)
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    conn = ctx.wrap_socket(raw, server_hostname=host)
    path = f"/api/v1/admin/sessions/{sid}/live?token={tok}"
    req = ("GET " + path + " HTTP/1.1\r\n"
           "Host: " + f"{host}:{port}" + "\r\n"
           "Upgrade: websocket\r\n"
           "Connection: Upgrade\r\n"
           "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
           "Sec-WebSocket-Version: 13\r\n\r\n")
    conn.sendall(req.encode())
    buf = b""
    while b"\r\n\r\n" not in buf:
        chunk = conn.recv(4096)
        if not chunk:
            return conn, "EOF"
        buf += chunk
    head = buf.split(b"\r\n\r\n")[0]
    try:
        return conn, int(head.split(b" ")[1])
    except (IndexError, ValueError):
        return conn, head[:40]

conn, st = handshake(999999, token)
if st != 101:
    print(f"  [FAIL] 有效 token 握手期望 101，实际 {st}")
else:
    conn.settimeout(5)
    try:
        frame = conn.recv(4096)
    except socket.timeout:
        frame = b""
    if b"EVT_CONNECTED" in frame:
        print("  [OK]   有效 token：握手 101，首帧 EVT_CONNECTED")
    else:
        print(f"  [FAIL] 有效 token 握手 101 但首帧不含 EVT_CONNECTED：{frame[:60]!r}")
    conn.close()

conn, st = handshake(999999, "bad")
if st != 101:
    print(f"  [FAIL] 坏 token 握手期望 101（鉴权后置），实际 {st}")
else:
    conn.settimeout(5)
    try:
        data = conn.recv(4096)
        closed = (len(data) == 0) or (data[0] == 0x88)  # 0x88 = close 帧
    except (socket.timeout, ConnectionError, OSError):
        closed = False
    if closed:
        print("  [OK]   坏 token：握手放行后服务端主动断开（鉴权后置生效）")
    else:
        print("  [FAIL] 坏 token 握手后服务端 5s 内未断开")
    conn.close()
PY
)
  printf '%s\n' "$ws_out"
  if printf '%s' "$ws_out" | grep -q FAIL; then fail=1; fi
fi

echo ""
echo "=== 7. 人脸接口 8MB 上限与限流 ==="
big=$(head -c 9000000 /dev/zero | tr '\0' 'A' | "${CURL[@]}" -o /dev/null -w '%{http_code}' \
      -X POST "$HTTPS/face-api/challenge/start" -F "frame=@-;filename=big.jpg" 2>/dev/null || true)
if [ "$big" = "413" ] || [ "$big" = "400" ]; then ok "9MB 请求被拒（${big}）"; else bad "9MB 请求期望 413，实际 ${big}"; fi
codes=$(for _ in $(seq 1 40); do
          "${CURL[@]}" -o /dev/null -w '%{http_code} ' "$HTTPS/face-api/health"
        done)
if printf '%s' "$codes" | grep -q 429; then ok "超过 10r/s 触发 429 限流"; else bad "未触发限流（40 次连打都是 200）"; fi

echo ""
if [ "$fail" -eq 0 ]; then echo "结论：全部通过"; else echo "结论：存在失败项" >&2; fi
exit "$fail"
