#!/usr/bin/env bash
# =====================================================================
# 等待全栈 healthy 并计时（Gate：全新机器 10 分钟内全部 healthy）
# =====================================================================
# 用法：./scripts/wait-healthy.sh [超时秒数]
# 输出：每个服务从脚本启动到 healthy 的耗时，最后一行是总耗时。
# 兼容 macOS 自带 bash 3.2：不用 declare -A。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
TIMEOUT="${1:-600}"
SERVICES=(mysql redis java face agent nginx)

start=$(date +%s)
while :; do
  now=$(date +%s)
  elapsed=$((now - start))
  pending=0
  done_count=0
  line=""
  for svc in "${SERVICES[@]}"; do
    eval "prev=\${DONE_AT_${svc}:-}"
    if [ -n "$prev" ]; then
      done_count=$((done_count + 1))
      continue
    fi
    cid=$(docker compose ps -q "$svc" 2>/dev/null || true)
    state="missing"
    if [ -n "$cid" ]; then
      state=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo unknown)
    fi
    if [ "$state" = "healthy" ]; then
      eval "DONE_AT_${svc}=${elapsed}"
      line+="  ${svc}=${elapsed}s"
    else
      # nginx 的 healthcheck 依赖 curl，短暂 running 是正常的
      pending=$((pending + 1))
    fi
  done

  if [ "$pending" -eq 0 ] && [ "$done_count" -eq "${#SERVICES[@]}" ]; then
    echo ""
    echo "全部服务已 healthy："
    for svc in "${SERVICES[@]}"; do
      eval "at=\${DONE_AT_${svc}:-?}"
      printf '  %-6s %ss\n' "$svc" "$at"
    done
    echo ""
    echo "总耗时：${elapsed}s（上限 ${TIMEOUT}s）"
    if [ "$elapsed" -le "$TIMEOUT" ]; then
      echo "GATE: PASS"
      exit 0
    fi
    echo "GATE: FAIL — 超过 ${TIMEOUT}s"
    exit 1
  fi

  if [ "$elapsed" -ge "$TIMEOUT" ]; then
    echo ""
    echo "超时 ${TIMEOUT}s，仍未 healthy 的服务："
    docker compose ps
    echo "GATE: FAIL"
    exit 1
  fi

  printf '\r等待中 %ss（剩余 %s 个服务）%s' "$elapsed" "$pending" "$line"
  sleep 3
done
