-- =====================================================================
-- V10 · CR-028 人脸采集「单独同意」事件记录
--
-- 背景：勾选框不构成 PIPL §29 意义上的有效「单独同意」（人脸属敏感个人信息）。
-- 同意/撤回是不可否认的法律事件，必须留下不可变的事件流；account.face_consent_at /
-- face_consent_version 只保留「当前态」供展示，事件表才是事实来源（CR-028）。
--
-- 表被删除权/撤回流程共用：WITHDRAW 行由学生本人删除人脸数据时写入
-- （FaceEnrollService.deleteOwnData），consent_version 记录被撤回时生效的版本。
-- 不建物理外键（§5.3 口径）。
-- =====================================================================

CREATE TABLE `face_consent_record` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `account_id`      BIGINT       NOT NULL COMMENT '同意主体 account.id',
  `student_id`      BIGINT       NULL     COMMENT '学生 id（冗余，便于按学生检索与审计）',
  `action`          VARCHAR(16)  NOT NULL COMMENT 'ACCEPT=同意采集 WITHDRAW=撤回同意',
  `consent_version` VARCHAR(16)  NULL     COMMENT '同意书版本；WITHDRAW 行记录被撤回时生效的版本',
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间',
  PRIMARY KEY (`id`),
  KEY `idx_fcr_account` (`account_id`, `created_at`),
  KEY `idx_fcr_student` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人脸采集单独同意事件流，只增不改（CR-028）';
