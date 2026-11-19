-- =====================================================================
-- V2 · 补齐「应到名单快照」（B1 的 DDL 遗漏）
--
-- 为什么必须有这张表：
--   contracts/openapi/java-api.yaml 的 Session.expectedCount 写明是「应到人数快照」，
--   文档 §14.3 也写了「应到名单快照」。但 V1 的 checkin_session 既没有
--   expected_count 列，也没有快照表。
--
--   不能用「查 students 表现算」代替：转班是改 student.class_id（§5.3.1），
--   一旦学生转走，历史会话的应到人数就会跟着变——那等于篡改考勤记录。
--   点名那一刻的名单必须冻结。
--
-- 语义：
--   - 创建会话时按 `students WHERE class_id=? AND status='ACTIVE'` 快照写入；
--   - expectedCount = count(*) from session_expected where session_id=?；
--   - 快照与会话同生共死（删除会话时一并清理，本系统不提供删除会话）。
-- =====================================================================

CREATE TABLE `session_expected` (
  `session_id` BIGINT     NOT NULL,
  `student_id` BIGINT     NOT NULL,
  `class_id`   BIGINT     NOT NULL COMMENT '快照时的班级，便于排查转班',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`session_id`, `student_id`),
  KEY `idx_se_student` (`student_id`) COMMENT '学生查「我该到而未到」的历史会话'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话应到名单快照：点名那一刻冻结';
