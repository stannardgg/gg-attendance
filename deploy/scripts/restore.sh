#!/usr/bin/env bash
# =====================================================================
# 备份恢复演练（Gate：备份必须实际可恢复）
# =====================================================================
# 用法：./scripts/restore.sh backups/face_attendance-YYYYmmdd-HHMMSS.sql.gz[.enc]
#
# 设计：恢复到**独立库** fa_restore_<时间戳>，不覆盖生产库；
# 逐个关键表比对行数，全部一致才算恢复成功。演练结束后打印导入耗时。
# 加密备份（CR-012，.enc 后缀）需要 .env 里的 BACKUP_AES_KEY，口令错会在
# openssl 阶段直接报 bad decrypt 退出，不会污染目标库。
# =====================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
set -a; [ -f .env ] && . ./.env; set +a

FILE="${1:?用法：$0 <备份文件.sql.gz>}"
[ -f "$FILE" ] || { echo "备份文件不存在：$FILE" >&2; exit 1; }

SRC_DB="${MYSQL_DATABASE:-face_attendance}"
DST_DB="fa_restore_$(date +%H%M%S)"
TABLES=(account teacher student class_group course teacher_scope checkin_session attendance face_vector face_profile appeal audit_log agent_query_log agent_action_log sys_config)

# 容器内执行 SQL：用单引号包住整条命令，避免宿主 shell 展开 $MYSQL_ROOT_PASSWORD
sql_root() {
  docker compose exec -T mysql sh -c "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $*"
}

# 按后缀选择解压管道：.enc 先解密（BACKUP_AES_KEY 走 .env 的 set -a 导出）
case "$FILE" in
  *.enc)
    if [ -z "${BACKUP_AES_KEY:-}" ]; then
      echo "[restore] 失败：加密备份需要 .env 里的 BACKUP_AES_KEY" >&2
      exit 1
    fi
    DECRYPT_CMD=(openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$FILE" -pass env:BACKUP_AES_KEY)
    ;;
  *)
    DECRYPT_CMD=(cat "$FILE")
    ;;
esac

echo "[restore] 源：$FILE"
echo "[restore] 目标库：${DST_DB}（不覆盖 ${SRC_DB}）"

sql_root "-e \"DROP DATABASE IF EXISTS $DST_DB; CREATE DATABASE $DST_DB DEFAULT CHARSET utf8mb4;\""

start=$(date +%s)
"${DECRYPT_CMD[@]}" | gunzip -c | docker compose exec -T mysql sh -c \
  "exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $DST_DB"
end=$(date +%s)
echo "[restore] 导入完成，耗时 $((end - start))s"

echo ""
printf '%-20s %10s %10s %6s\n' "表" "$SRC_DB" "$DST_DB" "一致"
fail=0
for t in "${TABLES[@]}"; do
  a=$(sql_root "-N -B -e \"SELECT COUNT(*) FROM $t;\" $SRC_DB" | tr -d '\r')
  b=$(sql_root "-N -B -e \"SELECT COUNT(*) FROM $t;\" $DST_DB" | tr -d '\r')
  if [ "$a" = "$b" ]; then mark="OK"; else mark="DIFF"; fail=1; fi
  printf '%-20s %10s %10s %6s\n' "$t" "$a" "$b" "$mark"
done

echo ""
if [ "$fail" -eq 0 ]; then
  echo "GATE: PASS — 15 张表行数全部一致，恢复耗时 $((end - start))s"
  echo "[restore] 演练库保留在 ${DST_DB}，确认后可删除："
  echo "  docker compose exec mysql sh -c 'exec mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"DROP DATABASE $DST_DB;\"'"
else
  echo "GATE: FAIL — 存在行数不一致的表" >&2
  exit 1
fi
