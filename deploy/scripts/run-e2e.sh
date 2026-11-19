#!/usr/bin/env bash
# =====================================================================
# 16.3 十八个验收场景 · 一键跑（WP-13）
# =====================================================================
# 为什么要两阶段：
#   CASIA 是 112×112 的对齐人脸，放大到 720×1280 后活体模型只给 0.02~0.10 分。
#   这是数据集的天花板，不是系统缺陷 —— 所以「必须通过」的场景必须把服务端阈值
#   降到 0 才有意义；否则测的只是「CASIA 静图过不了活体」，一条恒假命题。
#   而场景 07（翻拍照片必须被拒）恰恰要用生产阈值 0.55 才成立。
#   于是：
#     阶段 A  LIVENESS_THRESHOLD=0     跑 17 条（含全部正向流程）
#     阶段 B  LIVENESS_THRESHOLD=0.55  只跑场景 07
#
# 用法：
#   ./scripts/run-e2e.sh              # 跑全部，输出汇总表
#   ./scripts/run-e2e.sh -k 13        # 只跑匹配的用例（调试用，单阶段）
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
COMPOSE=(docker compose --env-file .env.e2e --profile test)

if [ ! -f .env ]; then
  echo "缺少 deploy/.env —— 先 cp .env.example .env 并填好密码" >&2
  exit 1
fi

mkdir -p out
rm -f out/results.json out/results-a.json out/results-b.json out/facts.json

with_liveness() {                       # with_liveness <0|0.55>
  awk -v v="$1" 'BEGIN{done=0}
    /^LIVENESS_THRESHOLD=/ {print "LIVENESS_THRESHOLD=" v; done=1; next}
    {print}
    END{if(!done) print "LIVENESS_THRESHOLD=" v}' .env > .env.e2e
  chmod 600 .env.e2e
}

recreate_face() {                       # 让新阈值生效
  "${COMPOSE[@]}" up -d --force-recreate --no-deps face >/dev/null
  for _ in $(seq 1 60); do
    st=$(docker inspect -f '{{.State.Health.Status}}' "$("${COMPOSE[@]}" ps -q face)" 2>/dev/null || echo starting)
    [ "$st" = "healthy" ] && return 0
    sleep 2
  done
  echo "face 未在 120 秒内 healthy" >&2
  return 1
}

ensure_nginx() {                        # e2e 依赖 nginx healthy，先自愈一遍。
  # 背景：git rebase/checkout 会重写工作区文件，macOS 下 nginx.conf 的单文件
  # bind mount 会悬空（容器内 No such file）→ healthcheck 连挂 → e2e 起不来。
  local st
  st=$(docker inspect -f '{{.State.Health.Status}}' \
       "$("${COMPOSE[@]}" ps -q nginx 2>/dev/null)" 2>/dev/null || echo missing)
  if [ "$st" != "healthy" ]; then
    echo "nginx 状态 ${st} → 自动重建（若刚做过 git rebase，这是挂载失效自愈）"
    "${COMPOSE[@]}" up -d --force-recreate nginx >/dev/null
    for _ in $(seq 1 60); do
      st=$(docker inspect -f '{{.State.Health.Status}}' "$("${COMPOSE[@]}" ps -q nginx)" 2>/dev/null || echo starting)
      [ "$st" = "healthy" ] && break
      sleep 2
    done
    [ "$st" = "healthy" ] || { echo "nginx 未在 120 秒内 healthy" >&2; return 1; }
  fi
}

run_phase() {                           # run_phase <a|b> <marker-expr> <junit>
  local tag="$1" expr="$2" xml="$3"
  echo ""
  echo "=== 阶段 ${tag}：${expr} ==="
  "${COMPOSE[@]}" run --rm -T e2e \
    python -m pytest /e2e -q -p no:cacheprovider --tb=short \
    -m "$expr" --junitxml="/out/$xml" || true
  [ -f out/results.json ] && cp out/results.json "out/results-$tag.json"
  [ -f out/facts.json ] && cp out/facts.json "out/facts-$tag.json"
}

if [ "${1:-}" != "" ] && [ "$1" = "-k" ]; then
  # 调试模式：单阶段，用当前 .env 的阈值
  with_liveness "$(grep '^LIVENESS_THRESHOLD=' .env | cut -d= -f2)"
  ensure_nginx
  recreate_face
  "${COMPOSE[@]}" run --rm -T e2e \
    python -m pytest /e2e -q -p no:cacheprovider --tb=short -k "$2"
  exit $?
fi

with_liveness 0
ensure_nginx
recreate_face
run_phase a "not high_liveness" results-a.xml

with_liveness 0.55
recreate_face
run_phase b "high_liveness" results-b.xml

# 收尾：把 face 恢复成 .env 里的生产阈值
with_liveness "$(grep '^LIVENESS_THRESHOLD=' .env | cut -d= -f2)"
recreate_face
rm -f .env.e2e

python3 - <<'PY'
import json, os, sys

rows, bad = [], 0
for tag, label in (("a", "阶段A 阈值0"), ("b", "阶段B 阈值0.55")):
    p = f"out/results-{tag}.json"
    if not os.path.exists(p):
        print(f"缺少 {p}")
        bad += 1
        continue
    for r in json.load(open(p, encoding="utf-8")):
        ok = r["outcome"] == "passed"
        bad += 0 if ok else 1
        rows.append((r["name"], label, "PASS" if ok else r["outcome"].upper(), r["duration"]))

print()
print(f"{'场景':<28}{'阶段':<18}{'结果':<8}{'秒':>6}")
print("-" * 62)
for name, label, res, dur in rows:
    print(f"{name:<28}{label:<18}{res:<8}{dur:>6}")
print("-" * 62)
print(f"合计 {len(rows)} 条，失败 {bad} 条")
sys.exit(1 if bad else 0)
PY
