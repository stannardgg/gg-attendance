#!/usr/bin/env bash
# =====================================================================
# MySQL 每日备份（§14.3 WP-12）
# =====================================================================
# 用法：./scripts/backup.sh [输出目录]
# 产物：backups/face_attendance-YYYYmmdd-HHMMSS.sql.gz + .sha256
# 保留：默认 7 天（BACKUP_KEEP_DAYS 可改）
#
# 注意：dump 在容器内执行，不落宿主机临时文件；备份文件不要放回仓库。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; [ -f .env ] && . ./.env; set +a

OUT_DIR="${1:-backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
DB="${MYSQL_DATABASE:-face_attendance}"
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT_DIR"

FILE="$OUT_DIR/${DB}-${STAMP}.sql.gz"

echo "[backup] 开始：$DB → $FILE"
docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  | gzip -9 > "$FILE"

# 校验：gzip 可解 + dump 尾部标记完整
gzip -t "$FILE"
if ! gzip -dc "$FILE" | tail -5 | grep -q "Dump completed"; then
  echo "[backup] 失败：dump 尾部缺少 'Dump completed'，文件不完整" >&2
  exit 1
fi

# macOS 自带的是 shasum，Linux 才是 sha256sum —— 两处都要能跑
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$FILE" | tee "$FILE.sha256"
else
  shasum -a 256 "$FILE" | tee "$FILE.sha256"
fi
SIZE=$(du -h "$FILE" | cut -f1)
echo "[backup] 完成：${FILE}（${SIZE}）"

# 清理超过保留期的备份
find "$OUT_DIR" -name "${DB}-*.sql.gz" -mtime +"$KEEP_DAYS" -print -delete 2>/dev/null || true
