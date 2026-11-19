#!/usr/bin/env bash
# =====================================================================
# MySQL 首次初始化：只读账号 face_ro（§12.3）
# =====================================================================
# 只在数据目录为空时由官方镜像的 entrypoint 执行一次。
# 该账号供运维排查与「最小权限」审计用；业务进程（java）用 MYSQL_USER，不使用它。
#
# ★ 为什么是库级 SELECT 而不是 face_vector 表级：
#   本脚本在 mysqld 初始化的**最后一刻**执行，此时 Flyway 还没跑（建表在 Java 启动时），
#   表级授权会直接 1146 Table doesn't exist，并让 entrypoint 以非 0 退出、
#   进而把 mysql 判成 unhealthy —— 整个栈起不来。
#   库级 SELECT 仍然是只读的（只有 SELECT，没有写权限），权限范围比"只 face_vector"
#   宽一点，但换来的是"全新机器能一次起来"。要收窄到表级，必须在 Java 迁移之后
#   由应用自己执行一条 GRANT，见 docs/handover/WP-12.md 的遗留问题。
# =====================================================================
set -euo pipefail

: "${MYSQL_FACE_RO_USER:?MYSQL_FACE_RO_USER 未设置}"
: "${MYSQL_FACE_RO_PASSWORD:?MYSQL_FACE_RO_PASSWORD 未设置}"
DB="${MYSQL_DATABASE:-face_attendance}"

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
CREATE USER IF NOT EXISTS '${MYSQL_FACE_RO_USER}'@'%' IDENTIFIED BY '${MYSQL_FACE_RO_PASSWORD}';
GRANT SELECT ON \`${DB}\`.* TO '${MYSQL_FACE_RO_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL

echo "[init] ${MYSQL_FACE_RO_USER} 已创建（库级只读）"
