#!/usr/bin/env bash
# =====================================================================
# 冷启动验收（WP-12 出口 Gate：全新机器 10 分钟内全部 healthy）
# =====================================================================
# 做的是"全新机器"能复现的最严动作：
#   1) 删掉所有容器、网络、匿名卷
#   2) 删掉 MySQL/Redis 的绑定目录 deploy/data（等于从空盘开始）
#   3) 重新 build 镜像（--no-cache 可选；默认用层缓存，模拟"机器上已有源码"）
#   4) up -d，轮询到六服务全部 healthy，打印总耗时与每个服务的耗时
#
# 用法：
#   ./scripts/verify-coldstart.sh            # 用层缓存（日常）
#   ./scripts/verify-coldstart.sh --rebuild  # 强制重建镜像（首次上机的真实耗时）
#
# 退出码 0 = 10 分钟内全部 healthy。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; [ -f .env ] && . ./.env; set +a

REBUILD=0
[ "${1:-}" = "--rebuild" ] && REBUILD=1
LIMIT="${COLDSTART_LIMIT_SEC:-600}"
SERVICES=(mysql redis java face agent nginx)

echo "=== 0. 清场 ==="
docker compose --env-file .env down -v --remove-orphans >/dev/null 2>&1 || true
rm -rf data
echo "  容器/网络/数据目录已清空"

t0=$(date +%s)
if [ "$REBUILD" -eq 1 ]; then
  echo ""
  echo "=== 1. 构建镜像（--no-cache）==="
  BUILDX_CONFIG=/tmp/bx docker compose --env-file .env build --no-cache 2>&1 | tail -3
else
  echo ""
  echo "=== 1. 构建镜像（层缓存）==="
  BUILDX_CONFIG=/tmp/bx docker compose --env-file .env build 2>&1 | tail -3
fi
t_build=$(( $(date +%s) - t0 ))
echo "  构建耗时 ${t_build}s"

echo ""
echo "=== 2. 启动 ==="
docker compose --env-file .env up -d 2>&1 | tail -3

echo ""
echo "=== 3. 等待全部 healthy ==="
# 兼容 macOS 自带 bash 3.2：不用 declare -A，用动态变量名记录各服务首次 healthy 的秒数
for _ in $(seq 1 $((LIMIT / 2))); do
  all=1
  for svc in "${SERVICES[@]}"; do
    cid=$(docker compose --env-file .env ps -q "$svc" 2>/dev/null || true)
    st="missing"
    if [ -n "$cid" ]; then
      st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo starting)
    fi
    if [ "$st" = "healthy" ]; then
      eval "prev=\${seen_${svc}:-}"
      if [ -z "$prev" ]; then
        eval "seen_${svc}=$(( $(date +%s) - t0 ))"
        printf '  %-6s healthy  %3ss\n' "$svc" "$(( $(date +%s) - t0 ))"
      fi
    else
      all=0
    fi
  done
  [ "$all" -eq 1 ] && break
  sleep 2
done

total=$(( $(date +%s) - t0 ))
echo ""
printf '总计：从清场到六服务全部 healthy = %ss（构建 %ss + 启动 %ss）\n' \
  "$total" "$t_build" "$(( total - t_build ))"
echo "Gate 上限：${LIMIT}s"

if [ "$total" -le "$LIMIT" ]; then
  echo "GATE: PASS"
  ./scripts/healthcheck.sh || true
  exit 0
fi
echo "GATE: FAIL —— 超过 ${LIMIT}s" >&2
docker compose ps
exit 1
