#!/usr/bin/env bash
# =====================================================================
# 契约断言：扫描数据库结构，发现「课程-班级耦合」即失败
# =====================================================================
# 归属工作包：WP-01（B1），由 WP-13 在 CI 中定期执行
# 事实来源  ：项目文档.md §5.3.1「绝对不允许出现的字段」、§14.3 WP-13、§16.3 场景 16
#
# 为什么需要这个脚本：
#   D7「班级与课程彻底解耦」是整个系统的地基。但它约束的是「不存在的东西」——
#   代码评审看不出多出来的一列，ORM 也不会因为多了 class_id 报错。
#   等到有人写 `course.setClassId(...)` 时，已经晚了。所以用 information_schema 兜底。
#
# 用法：
#   ./check-contract.sh                      # 用本机 mysql 客户端
#   MYSQL_CLI="docker exec -i fa-mysql-gate mysql" ./check-contract.sh
#
# 环境变量：
#   MYSQL_HOST / MYSQL_PORT / MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE
#   MYSQL_CLI   可选，完整的客户端命令前缀（默认 "mysql"）
# =====================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
MYSQL_DATABASE="${MYSQL_DATABASE:-face_attendance}"
MYSQL_CLI="${MYSQL_CLI:-mysql}"

# shellcheck disable=SC2206
CLI=($MYSQL_CLI)
CLI+=(-h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -N -B)
[ -n "$MYSQL_PASSWORD" ] && CLI+=("-p${MYSQL_PASSWORD}")

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
fail=0

say() { printf '%s\n' "$*"; }
ok()  { printf '%s  %s%s\n' "$GREEN[OK]" "$*" "$RESET"; }
bad() { printf '%s  %s%s\n' "$RED[VIOLATION]" "$*" "$RESET"; fail=1; }
warn(){ printf '%s  %s%s\n' "$YELLOW[WARN]" "$*" "$RESET"; }

sql() { "${CLI[@]}" -e "$1" "$MYSQL_DATABASE"; }

say "=== 契约断言：D7 班级与课程解耦 ==="
say "库: $MYSQL_DATABASE @ $MYSQL_HOST:$MYSQL_PORT"
say ""

# ---------------------------------------------------------------------
# 1. 禁用字段：任何表出现这些「列」即违规
# ---------------------------------------------------------------------
# 格式：表名|列名|为什么禁止
FORBIDDEN_COLUMNS=(
  "course|class_id|课程不得指向班级（D7 核心约束）"
  "course|class_group_id|同上，换个名字也是耦合"
  "course|grade|年级属于班级维度，课程不该有"
  "course|major|专业属于班级维度，课程不该有"
  "class_group|course_id|班级不得指向课程"
  "class_group|course_ids|同上"
  "student|course_id|学生不选课（v2.0 无 course_student）"
)

say "--- 1/3 禁用字段扫描 ---"
for entry in "${FORBIDDEN_COLUMNS[@]}"; do
  IFS='|' read -r tbl col why <<< "$entry"
  hit=$(sql "SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema='$MYSQL_DATABASE' AND table_name='$tbl' AND column_name='$col';")
  if [ "$hit" != "0" ]; then
    bad "$tbl.$col 存在 —— $why"
  fi
done
if [ "$fail" -eq 0 ]; then ok "未发现任何「课程-班级」耦合字段"; fi

# ---------------------------------------------------------------------
# 2. 禁用表：选课表与任何「课程×班级」映射表
# ---------------------------------------------------------------------
say ""
say "--- 2/3 禁用表扫描 ---"
for t in course_student course_class class_course course_class_group; do
  hit=$(sql "SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema='$MYSQL_DATABASE' AND table_name='$t';")
  if [ "$hit" != "0" ]; then
    bad "表 $t 存在 —— v2.0 明确不建选课表/映射表（§5.2、D7）"
  fi
done

# 模糊匹配：任何同时含 course 与 class 的表名都视为可疑
suspect=$(sql "SELECT table_name FROM information_schema.tables
               WHERE table_schema='$MYSQL_DATABASE'
                 AND (table_name LIKE '%course%class%' OR table_name LIKE '%class%course%');")
if [ -n "$suspect" ]; then
  while IFS= read -r line; do
    [ -n "$line" ] && bad "可疑表名：$line（同时含 course 与 class）"
  done <<< "$suspect"
fi
if [ "$fail" -eq 0 ]; then ok "未发现选课表或课程-班级映射表"; fi

# ---------------------------------------------------------------------
# 3. 反向断言：15 张表必须都在
#    （防止迁移只跑了一半却「恰好没有违规」而通过）
# ---------------------------------------------------------------------
say ""
say "--- 3/3 必需表齐全性 ---"
EXPECTED_TABLES=(
  account teacher student class_group course teacher_scope
  checkin_session attendance face_vector face_profile appeal
  audit_log agent_query_log agent_action_log sys_config
)
missing=()
for t in "${EXPECTED_TABLES[@]}"; do
  hit=$(sql "SELECT COUNT(*) FROM information_schema.tables
             WHERE table_schema='$MYSQL_DATABASE' AND table_name='$t';")
  [ "$hit" = "0" ] && missing+=("$t")
done
if [ ${#missing[@]} -gt 0 ]; then
  bad "缺少表：${missing[*]}"
else
  ok "15 张表齐全"
fi

# ---------------------------------------------------------------------
# 4. 单管理员唯一索引存在（最容易被"优化"掉的一项）
# ---------------------------------------------------------------------
say ""
idx=$(sql "SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema='$MYSQL_DATABASE' AND table_name='account'
             AND index_name='uk_single_admin';")
if [ "$idx" = "0" ]; then
  bad "account.uk_single_admin 缺失 —— 单管理员约束失效"
else
  ok "uk_single_admin 存在"
fi

say ""
if [ "$fail" -ne 0 ]; then
  printf '%s契约断言失败：禁止的字段或表已进入数据库。%s\n' "$RED" "$RESET"
  printf '处置：不要在业务代码里绕，先改 DDL 并提 CR（docs/change-request/）。\n'
  exit 1
fi
printf '%s契约断言通过：班级与课程保持解耦。%s\n' "$GREEN" "$RESET"
