-- ---------------------------------------------------------------------
-- V7：刷脸失败计数落库（CR-023）
--
-- 背景：项目文档 WP-07 的验收条款「第 4 次重试 → FA-3005」此前只做在采集页
-- 的本地计数里（ResultPanel.attempts），关掉页面回到首页再点进去，次数清零，
-- 可以无限重试。本迁移把「每会话每人失败次数」变成**权威数据**：
--
--   1. 计数落 MySQL 而不是 Redis：这是业务规则（门禁）而不是缓存。
--      项目既有先例——改密失效用 credential_version 而不用 Redis 黑名单，
--      理由是 Redis 故障时的降级方向不可接受；失败计数同理。
--   2. 谁写：Python finish 判失败（rejectGate != commit）后经内部接口
--      POST /internal/checkin/attempt/fail 上报。单一写入口原则（§1.2 D8）
--      不破坏——Python 只提交判定结果，落库仍只有 Java。
--   3. 谁读：join（≥ 上限 → FA-3005）、sessions/active（回填 retryLeft，
--      首页据此把「待签到」改成「识别失败」并拒绝点击）、Python start
--      （防止旧票据在达到上限后还能再开一场挑战）。
--   4. 无物理外键，理由同 V1 头部三条全局约定；(session_id, student_id)
--      唯一键既是查询路径也是 upsert 锚点。
--
-- 同时把 sys_config.CHECKIN_MAX_RETRY 的种子值 5 修正为 3：项目文档 §4.4.3
-- 与错误码 FA-3005 的 note（「每会话每人 3 次」）一直是 3，V3 的 5 是笔误。
-- 不能直接改 V3（Flyway validate-on-migrate 会拒），在此补一条 UPDATE。
-- 该键 editable=1，管理员后续可热调；Java 侧读取顺序见 CheckinAttemptService。
-- ---------------------------------------------------------------------

CREATE TABLE `checkin_attempt` (
  `id`           BIGINT      NOT NULL AUTO_INCREMENT,
  `session_id`   BIGINT      NOT NULL COMMENT '签到会话，逻辑关联 checkin_session.id',
  `student_id`   BIGINT      NOT NULL COMMENT '学生，逻辑关联 student.id',
  `fail_count`   INT         NOT NULL DEFAULT 0 COMMENT '累计识别失败次数（finish 判失败 + no_face/multi_face 中止）',
  `last_gate`    VARCHAR(24) NULL     COMMENT '最近一次失败的门禁：liveness/identity/action_count/match/multi_face/no_face',
  `last_fail_at` DATETIME(3) NOT NULL COMMENT '最近一次失败时间',
  `created_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attempt_session_student` (`session_id`, `student_id`),
  KEY `idx_attempt_student` (`student_id`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每会话每人的刷脸失败计数（达到上限后 join 以 FA-3005 拒绝）';

UPDATE `sys_config`
   SET `config_value` = '3',
       `description`  = '单场签到允许的识别失败次数（FA-3005）；CR-023 由 5 修正为 3'
 WHERE `config_key` = 'CHECKIN_MAX_RETRY';
