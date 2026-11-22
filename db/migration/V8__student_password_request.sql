-- ---------------------------------------------------------------------
-- V7：学生密码修改申请（CR-024）
--
-- 背景：V5/V6 让教师的资料与凭据都走「申请 → 审核」，但学生忘了密码仍然
-- 只能线下找管理员。本迁移给学生开同一条链：学生在「我的」页提交改密申请
-- → 管理员审核 → 通过才写 account.password_hash，并 cv+1 使旧令牌失效。
--
-- 三个刻意的设计：
--   1. 独立建表而不是扩展 teacher_profile_request：那张表 teacher_id NOT NULL
--      且 uk_tpr_pending 生成列绑定 teacher_id，塞 student_id 进去语义就乱了；
--      学生申请当前只有密码一项，列集比教师表小得多。
--   2. 没有 old_* 快照、没有 new_username 等资料字段——学号是登录名且由
--      管理员维护，学生侧本次只放开「改密」一种申请；将来若放开姓名等
--      资料，照 V6 的方式 ALTER 加列即可。
--   3. 密码只存 BCrypt 哈希且只暂存：通过/驳回/撤回后立即清空，
--      明文不落库、不进日志、不进审计。同一学生同时只允许一条 PENDING，
--      用生成列 + 唯一索引兜底（uk_spr_pending），业务代码不得读写生成列。
-- ---------------------------------------------------------------------

CREATE TABLE `student_profile_request` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `student_id`        BIGINT       NOT NULL COMMENT '申请人 student.id',
  `account_id`        BIGINT       NOT NULL COMMENT '申请人 account.id，冗余存储便于审计与自查',
  `new_password_hash` VARCHAR(100) NULL COMMENT '新密码的 BCrypt 哈希，仅审核前暂存，事后立即清空',
  `reason`            VARCHAR(500) NULL COMMENT '学生填写的申请说明',
  `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                      COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN',
  `reviewer_id`       BIGINT       NULL COMMENT '审核人 account.id（仅 ADMIN）',
  `review_note`       VARCHAR(500) NULL COMMENT '审核意见；驳回时必填',
  `reviewed_at`       DATETIME(3)  NULL,
  `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `pending_student`   VARCHAR(32) GENERATED ALWAYS AS (
      CASE WHEN `status` = 'PENDING' THEN CAST(`student_id` AS CHAR) END) STORED
      COMMENT '仅用于 uk_spr_pending，业务代码不得读写',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spr_pending` (`pending_student`),
  KEY `idx_spr_student` (`student_id`, `created_at`),
  KEY `idx_spr_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生密码修改申请；PENDING 唯一由生成列保证';
