-- =====================================================================
-- V9 · 课程-学生授权表（CR-026）
--
-- 为什么要有这张表：
--   D7 把应到名单钉死在「班级全体在读学生」上，跨班选修、转班补授
--   的学生从此进不了原课程的签到。CR-026 在不动 D7 主体（course 仍无
--   classId、班级课程互不引用）的前提下，给课程加一张**授权表**：
--   把课程单独授权给指定学生，发起签到时并入应到快照。
--
-- 为什么它是授权表而不是选课表：
--   - 班级名单是默认应到，授权学生是追加（并集），语义不同；
--   - 一门课通常只授权个位数散生，没有「40 人 × 10 门课」的维护成本；
--   - 与 teacher_scope 同类：点名补充口径，不构成班级-课程业务关联。
--
-- 语义：
--   - 撤销授权 = 物理删除行（teacher_scope 同风格，审计有痕迹）；
--   - 无 status 列：授权名单只在会话创建那一刻被读，软删没有业务语义；
--   - 会话快照用 UNION 并入（SessionService.create），已存续会话不追溯
--     （CR-001：历史名单动了等于篡改考勤）。
-- =====================================================================

CREATE TABLE `course_student_grant` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT,
  `course_id`  BIGINT      NOT NULL,
  `student_id` BIGINT      NOT NULL,
  `granted_by` BIGINT      NULL     COMMENT '操作人（account.id），审计冗余',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_csg_course_student` (`course_id`, `student_id`),
  KEY `idx_csg_student` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程-学生授权（CR-026）：发起签到时并入应到快照';
