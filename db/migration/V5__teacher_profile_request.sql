-- ---------------------------------------------------------------------
-- V5：教师资料修改申请（CR-019）
--
-- 背景：教师的姓名 / 院系 / 职称只有 ADMIN 能改（TeacherService.update），
-- 教师本人发现自己资料有误时只能线下找管理员，改动也没有"谁申请、谁批准"的痕迹。
-- 本表把这条链变成：教师提交申请（只带要改的字段）→ 管理员审核 →
-- 通过时才写 teacher 表，并把改前改后写进 audit_log。
--
-- 三个刻意的设计：
--   1. old_* 是提交时刻的快照，不是审批时的实时值 —— 管理员看到的 diff 与教师
--      提交时看到的一致；即便中间有人改过 teacher，也仍按申请值落库（申请本身
--      就是一次显式意图），但审计会记录实时 before，不会被快照掩盖。
--   2. 只能改 name / department / title。工号是登录名（account.username 与
--      teacher.teacher_no 双写），改它等于改账号，仍走管理员编辑入口；
--      密码重置不属于"资料"，不在本表范围。
--   3. 同一教师只允许一条 PENDING：MySQL 没有部分索引，沿用 appeal 的做法，
--      用生成列 + 唯一索引；非 PENDING 行取 NULL 不参与唯一性判断。
-- ---------------------------------------------------------------------

CREATE TABLE `teacher_profile_request` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `teacher_id`     BIGINT       NOT NULL,
  `account_id`     BIGINT       NOT NULL COMMENT '申请人 account.id，冗余存储便于审计与自查',
  `old_name`       VARCHAR(64)  NULL     COMMENT '提交时刻的姓名快照',
  `old_department` VARCHAR(120) NULL,
  `old_title`      VARCHAR(64)  NULL,
  `new_name`       VARCHAR(64)  NULL     COMMENT '申请变更后的姓名；NULL 表示未申请该字段',
  `new_department` VARCHAR(120) NULL,
  `new_title`      VARCHAR(64)  NULL,
  `reason`         VARCHAR(500) NULL     COMMENT '教师填写的申请说明',
  `status`         VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                   COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN',
  `reviewer_id`    BIGINT       NULL     COMMENT '审核人 account.id（仅 ADMIN）',
  `review_note`    VARCHAR(500) NULL     COMMENT '审核意见；驳回时必填',
  `reviewed_at`    DATETIME(3)  NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `pending_teacher` VARCHAR(32) GENERATED ALWAYS AS (
      CASE WHEN `status` = 'PENDING' THEN CAST(`teacher_id` AS CHAR) END) STORED
      COMMENT '仅用于 uk_tpr_pending，业务代码不得读写',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpr_pending` (`pending_teacher`),
  KEY `idx_tpr_teacher` (`teacher_id`, `created_at`),
  KEY `idx_tpr_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师资料修改申请；PENDING 唯一由生成列保证';
