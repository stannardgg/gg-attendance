#!/usr/bin/env bash
# =====================================================================
# 全栈健康检查（Gate 证据用）
# =====================================================================
# 退出码 0 = 六个服务全部 healthy 且对外端点可访问。
# 输出按「容器状态 / 对外端点」两段，便于直接贴进验收报告。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; [ -f .env ] && . ./.env; set +a

HTTP_PORT="${HTTP_PORT:-80}"
HTTPS_PORT="${HTTPS_PORT:-443}"
BASE="https://127.0.0.1:${HTTPS_PORT}"

fail=0
say() { printf '%s\n' "$*"; }
ok()  { printf '  [OK]   %s\n' "$*"; }
bad() { printf '  [FAIL] %s\n' "$*"; fail=1; }

say "=== 1. 容器状态 ==="
docker compose ps --format 'table {{.Service}}\t{{.Status}}' || true

say ""
say "=== 2. 健康门（compose healthcheck）==="
for svc in mysql redis java face agent nginx; do
  cid=$(docker compose ps -q "$svc" 2>/dev/null || true)
  if [ -z "$cid" ]; then bad "$svc 未启动"; continue; fi
  state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")
  # 注意 bash 的 && 与 || 同优先级、左结合：写 a || b && c 会被解析成 (a || b) && c。
  # 第一版就是这么写的，结果除 nginx 外全部误判 FAIL。必须显式加括号。
  if [ "$state" = "healthy" ] || { [ "$state" = "running" ] && [ "$svc" = "nginx" ]; }; then
    ok "$svc: $state"
  else
    bad "$svc: $state"
  fi
done

say ""
say "=== 3. 对外端点（经 nginx）==="
check_url() {
  local name="$1" url="$2" expect="$3"
  local body
  body=$(curl -fsS -k --max-time 10 "$url" 2>/dev/null || true)
  if printf '%s' "$body" | grep -q "$expect"; then
    ok "$name → $url"
  else
    bad "${name} → ${url}（期望包含 ${expect}，实际：${body:0:120}）"
  fi
}

check_url "nginx 健康"   "$BASE/healthz"            "ok"
# Java 的 actuator 不对外暴露，用登录端点的结构化响应证明 Java 活着
# 故意用不存在的账号：登录必然失败并返回 {"code":"FA-xxxx",...}，这正好证明 Java 活着。
# 不能用 -f —— curl -f 对 4xx 会丢弃 body，第一版就是这样把所有业务错误判成"无响应"。
java_body=$(curl -sS -k --max-time 10 -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' -d '{"username":"__health__","password":"__health__"}' 2>/dev/null || true)
if printf '%s' "$java_body" | grep -q '"code"'; then ok "Java 登录端点有结构化响应"; else bad "Java 登录端点无响应"; fi

check_url "Face 健康"    "$BASE/face-api/health"     '"status":"UP"'
agent_code=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/agent/" || true)
if [ "$agent_code" = "200" ]; then ok "Agent 页面 → /agent/"; else bad "Agent 页面 HTTP $agent_code"; fi
admin_code=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/admin/" || true)
if [ "$admin_code" = "200" ]; then ok "管理端 → /admin/"; else bad "管理端 HTTP $admin_code"; fi
mobile_code=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/m/" || true)
if [ "$mobile_code" = "200" ]; then ok "移动端 → /m/"; else bad "移动端 HTTP $mobile_code"; fi

say ""
if [ "$fail" -eq 0 ]; then
  say "结论：全部通过"
else
  say "结论：存在失败项"
fi
exit "$fail"
