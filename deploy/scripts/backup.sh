#!/usr/bin/env bash
# =====================================================================
# MySQL 每日备份（§14.3 WP-12）
# =====================================================================
# 用法：./scripts/backup.sh [输出目录]
# 产物：backups/face_attendance-YYYYmmdd-HHMMSS.sql.gz[.enc] + .sha256
# 保留：默认 7 天（BACKUP_KEEP_DAYS 可改）
#
# 加密（CR-012）：.env 配置 BACKUP_AES_KEY 后，dump 经完整性校验后整体加密
# （AES-256-CBC + PBKDF2 600k 轮），明文 .sql.gz 不落盘；未配置则保持原行为
# 并打 WARN——备份里有全量人脸向量，生产环境必须配置。
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

PLAIN="$OUT_DIR/${DB}-${STAMP}.sql.gz"

echo "[backup] 开始：$DB → $PLAIN"
docker compose exec -T mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers --set-gtid-purged=OFF "$MYSQL_DATABASE"' \
  | gzip -9 > "$PLAIN"

# 校验：gzip 可解 + dump 尾部标记完整（必须在加密前完成，密文没法做这个检查）
gzip -t "$PLAIN"
if ! gzip -dc "$PLAIN" | tail -5 | grep -q "Dump completed"; then
  echo "[backup] 失败：dump 尾部缺少 'Dump completed'，文件不完整" >&2
  exit 1
fi

FILE="$PLAIN"
if [ -n "${BACKUP_AES_KEY:-}" ]; then
  if ! openssl enc -help 2>&1 | grep -q -- '-pbkdf2'; then
    echo "[backup] 失败：当前 openssl 不支持 -pbkdf2（老版 LibreSSL/OpenSSL），无法加密；明文 dump 已删除" >&2
    rm -f "$PLAIN"
    exit 1
  fi
  FILE="$PLAIN.enc"
  echo "[backup] 加密：$PLAIN → $FILE"
  if ! openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt \
      -in "$PLAIN" -out "$FILE" -pass env:BACKUP_AES_KEY; then
    rm -f "$PLAIN"   # 加密失败不留明文 dump 在盘上
    exit 1
  fi
  rm -f "$PLAIN"
else
  echo "[backup] 警告：BACKUP_AES_KEY 未配置，备份文件【不加密】（CR-012）" >&2
fi

# macOS 自带的是 shasum，Linux 才是 sha256sum —— 两处都要能跑（对最终产物取指纹）
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$FILE" | tee "$FILE.sha256"
else
  shasum -a 256 "$FILE" | tee "$FILE.sha256"
fi
SIZE=$(du -h "$FILE" | cut -f1)
echo "[backup] 完成：${FILE}（${SIZE}）"

# 清理超过保留期的备份（同时覆盖加密与未加密两代产物）
find "$OUT_DIR" -name "${DB}-*.sql.gz*" -mtime +"$KEEP_DAYS" -print -delete 2>/dev/null || true
