-- =====================================================================
-- 人脸签到系统 · 全库结构
-- =====================================================================
-- 归属工作包：WP-01（B1 契约冻结）
-- 事实来源  ：项目文档.md §5.2 表清单、§5.3 锁定表结构、§5.3.1 CRUD 约束
-- 方言      ：MySQL 8.0+ / InnoDB / utf8mb4
-- 字符集    ：全库 utf8mb4_general_ci（不支持 emoji 之外的排序需求时足够）
--
-- 三条全局约定（三端必须一致，见 §5.4 与 §6.6）：
--   1. 枚举一律大写英文字符串，不用数字码；
--   2. 时间一律 DATETIME(3)，接口层序列化为 ISO-8601 带时区；
--   3. 向量存 float32 小端序 BLOB，入库前 L2 归一化。
--
-- 外键策略（本文件刻意不建任何 FOREIGN KEY）：
--   理由一：签到链路的写热点在 attendance，物理外键会把 insert 变成跨表加锁；
--   理由二：跨域（org / checkin / appeal）由不同模块维护，物理外键把模块编译期解耦
--           变成部署期强耦合，违背 §9.1 的依赖方向；
--   理由三：历史数据要能长期保留（归档班级/课程仍可查），物理外键会挡住归档流程。
--   替代手段：所有关联字段一律建索引 + 应用层事务内校验 + 契约断言脚本兜底。
--
-- ★ 与 §5.3 锁定 DDL 的关系：五处锁定表（face_vector / class_group / course /
--   teacher_scope / checkin_session / attendance）逐字照抄，只做了两件加法：
--   （a）补 COMMENT 之外的索引 —— 已在字段旁标注「增补索引」；
--   （b）不改任何字段名、类型、可空性、默认值。
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. account：统一认证主体（ADMIN / TEACHER / STUDENT）
-- ---------------------------------------------------------------------
CREATE TABLE `account` (
  `id`                   BIGINT       NOT NULL AUTO_INCREMENT,
  `username`             VARCHAR(64)  NOT NULL COMMENT '登录名：管理员=自定义，教师=工号，学生=学号',
  `password_hash`        VARCHAR(72)  NOT NULL COMMENT 'BCrypt strength=10（60 字符），留余量到 72 便于将来换算法',
  `role`                 VARCHAR(16)  NOT NULL COMMENT 'ADMIN / TEACHER / STUDENT',
  `status`               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `face_consent_at`      DATETIME(3)  NULL     COMMENT '人脸采集「单独同意」的时间，未同意为 NULL（§13.4）',
  `face_consent_version` VARCHAR(16)  NULL     COMMENT '同意书版本号，便于回溯用户同意的是哪一版',
  `last_login_at`        DATETIME(3)  NULL,
  `created_at`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  -- 单管理员约束：ADMIN 行取 1，其余取 NULL；唯一索引允许多个 NULL，因此只能存在一个 ADMIN。
  -- 用生成列而不是触发器，是因为触发器在批量导入/迁移时容易被绕过，且错误信息不直观（§9.3）。
  `admin_singleton`      TINYINT      GENERATED ALWAYS AS (CASE WHEN `role` = 'ADMIN' THEN 1 ELSE NULL END) STORED
                                      COMMENT '仅用于 uk_single_admin，业务代码不得读写',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_username` (`username`),
  UNIQUE KEY `uk_single_admin` (`admin_singleton`),
  KEY `idx_account_role` (`role`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一认证主体；role 决定进哪个端';

-- ---------------------------------------------------------------------
-- 2. teacher：教师档案
-- ---------------------------------------------------------------------
CREATE TABLE `teacher` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `account_id`  BIGINT       NOT NULL,
  `teacher_no`  VARCHAR(32)  NOT NULL COMMENT '工号，同时作为 account.username',
  `name`        VARCHAR(64)  NOT NULL,
  `department`  VARCHAR(120) NULL     COMMENT '院系',
  `title`       VARCHAR(64)  NULL     COMMENT '职称',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teacher_account` (`account_id`),
  UNIQUE KEY `uk_teacher_no` (`teacher_no`),
  KEY `idx_teacher_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师档案；教师账号只能由 ADMIN 创建';

-- ---------------------------------------------------------------------
-- 3. student：学生档案（class_id 是唯一归属，一人同时只在一个班）
-- ---------------------------------------------------------------------
CREATE TABLE `student` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `account_id`  BIGINT       NULL     COMMENT '激活后才回填；未激活为 NULL（唯一索引允许多个 NULL）',
  `student_no`  VARCHAR(32)  NOT NULL COMMENT '学号，激活时作为 account.username',
  `name`        VARCHAR(64)  NOT NULL,
  `class_id`    BIGINT       NOT NULL COMMENT '★ 唯一归属班级；转班 = 改本字段 + 留审计',
  `status`      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / TRANSFERRED（已转出） / ARCHIVED',
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_no` (`student_no`),
  UNIQUE KEY `uk_student_account` (`account_id`),
  KEY `idx_student_class` (`class_id`, `status`) COMMENT '应到名单查询的唯一入口（§4.2）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生档案；应到名单 = 本表 class_id + status=ACTIVE';

-- ---------------------------------------------------------------------
-- 4. class_group：班级主数据（★ 刻意不设任何指向 course 的字段，见 D7）
-- ---------------------------------------------------------------------
CREATE TABLE `class_group` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)  NOT NULL COMMENT '班级邀请码，学生激活时使用；创建后不可修改',
  `name`             VARCHAR(120) NOT NULL COMMENT '如 计算机 2024 级 1 班',
  `grade`            VARCHAR(16)  NULL     COMMENT '年级，如 2024；仅作筛选维度',
  `major`            VARCHAR(120) NULL     COMMENT '专业；仅作筛选维度',
  `owner_teacher_id` BIGINT       NULL     COMMENT '负责教师（班主任）',
  `status`           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
  `remark`           VARCHAR(255) NULL,
  `created_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_code` (`code`),
  UNIQUE KEY `uk_class_name` (`name`),
  KEY `idx_class_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级主数据，与课程无关联';

-- ---------------------------------------------------------------------
-- 5. course：课程主数据（★ 刻意不含 class_id，也不建 course_student，见 D7）
-- ---------------------------------------------------------------------
CREATE TABLE `course` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `code`             VARCHAR(32)  NOT NULL COMMENT '课程编码，如 MATH101；创建后不可修改',
  `name`             VARCHAR(120) NOT NULL COMMENT '如 高等数学（上）；允许重名，唯一性靠 code',
  `semester`         VARCHAR(32)  NULL     COMMENT '如 2026-2027-1；仅用于筛选与展示',
  `owner_teacher_id` BIGINT       NULL     COMMENT '开课教师',
  `status`           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
  `remark`           VARCHAR(255) NULL,
  `created_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_course_code` (`code`),
  KEY `idx_course_status` (`status`),
  KEY `idx_course_semester` (`semester`) COMMENT '增补索引：课程列表按学期筛选（§8.4.2）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程主数据，不含任何班级信息';

-- ---------------------------------------------------------------------
-- 6. teacher_scope：教师授权范围（授权表，不是业务关联表）
-- ---------------------------------------------------------------------
CREATE TABLE `teacher_scope` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `teacher_id`  BIGINT      NOT NULL,
  `scope_type`  VARCHAR(8)  NOT NULL COMMENT 'CLASS / COURSE',
  `scope_id`    BIGINT      NOT NULL COMMENT '对应 class_group.id 或 course.id',
  `granted_by`  BIGINT      NULL     COMMENT '授权人（ADMIN 的 account.id）',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ts` (`teacher_id`, `scope_type`, `scope_id`),
  KEY `idx_ts_scope` (`scope_type`, `scope_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师可见范围，由 ADMIN 维护';

-- ---------------------------------------------------------------------
-- 7. checkin_session：签到会话 = 班级 × 课程（两者独立引用，互不校验关联）
-- ---------------------------------------------------------------------
CREATE TABLE `checkin_session` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT,
  `class_id`       BIGINT       NOT NULL COMMENT '★ 本次点名的班级（决定应到名单）',
  `course_id`      BIGINT       NOT NULL COMMENT '★ 本次签到的课程（仅用于统计口径）',
  `teacher_id`     BIGINT       NOT NULL,
  `title`          VARCHAR(120) NOT NULL,
  `status`         VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / CLOSED / CANCELLED',
  `start_at`       DATETIME(3)  NOT NULL,
  `end_at`         DATETIME(3)  NOT NULL,
  `late_after_sec` INT          NOT NULL DEFAULT 300,
  `qr_epoch`       INT          NOT NULL DEFAULT 0 COMMENT '递增可作废旧码（教师「换码」）',
  `allow_appeal`   TINYINT(1)   NOT NULL DEFAULT 1,
  `closed_at`      DATETIME(3)  NULL,
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_cs_class` (`class_id`, `status`),
  KEY `idx_cs_course` (`course_id`, `status`),
  KEY `idx_cs_status_end` (`status`, `end_at`) COMMENT '过期扫描定时任务（每 30s）的唯一入口',
  KEY `idx_cs_teacher` (`teacher_id`, `status`) COMMENT '增补索引：教师查「我发起的会话」，锁定 DDL 未覆盖'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到会话=班级×课程，二者独立';

-- ---------------------------------------------------------------------
-- 8. attendance：考勤记录（唯一键是防重复签到的最后一道防线）
-- ---------------------------------------------------------------------
CREATE TABLE `attendance` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `session_id`        BIGINT       NOT NULL,
  `student_id`        BIGINT       NOT NULL,
  `status`            VARCHAR(16)  NOT NULL COMMENT 'PRESENT / LATE / ABSENT / LEAVE / EXEMPT',
  `checkin_at`        DATETIME(3)  NULL,
  `similarity`        DECIMAL(5,4) NULL,
  `liveness_median`   DECIMAL(5,4) NULL COMMENT '多帧活体概率中位数（门禁1）',
  `identity_sim`      DECIMAL(5,4) NULL COMMENT '首末帧余弦相似度（门禁2）',
  `action_count`      TINYINT      NULL COMMENT '实际检测到的动作次数（门禁3）',
  `reject_gate`       VARCHAR(16)  NULL COMMENT '失败时被哪道门禁拒：liveness/identity/action_count/multi_face/no_face/match',
  `matched_vector_id` BIGINT       NULL,
  `action_seq`        VARCHAR(64)  NULL COMMENT '如 MOUTH_ONCE / MOUTH_TWICE',
  `device_hash`       CHAR(64)     NULL,
  `client_ip`         VARCHAR(45)  NULL,
  `snapshot_ref`      VARCHAR(255) NULL,
  `adjust_reason`     VARCHAR(255) NULL,
  `adjusted_by`       BIGINT       NULL,
  `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_att_session_student` (`session_id`, `student_id`) COMMENT '幂等键：同一会话同一学生只能有一条记录',
  KEY `idx_att_student` (`student_id`, `created_at`),
  KEY `idx_att_session_status` (`session_id`, `status`) COMMENT '增补索引：大屏统计各状态人数，锁定 DDL 未覆盖'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='考勤记录；uk_att_session_student 同时充当 commit 幂等键';

-- ---------------------------------------------------------------------
-- 9. face_vector：人脸向量（只读消费方为 Python，写入方为 Java）
-- ---------------------------------------------------------------------
CREATE TABLE `face_vector` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `student_id`    BIGINT       NOT NULL,
  `model_name`    VARCHAR(32)  NOT NULL COMMENT '如 buffalo_l/arcface-r50',
  `dim`           SMALLINT     NOT NULL DEFAULT 512,
  `embedding`     BLOB         NOT NULL COMMENT 'float32 小端序，已 L2 归一化',
  `quality_score` DECIMAL(4,3) NOT NULL COMMENT '0~1，模糊/亮度/姿态综合',
  `yaw`           DECIMAL(5,2) NULL,
  `pitch`         DECIMAL(5,2) NULL,
  `brightness`    DECIMAL(5,2) NULL,
  `source_ref`    VARCHAR(255) NULL COMMENT '加密后的采集图对象键',
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_fv_student` (`student_id`),
  KEY `idx_fv_created` (`created_at`) COMMENT '增补索引：Python 端每 60s 按 created_at 增量刷新（§6.4 方式 A）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人脸向量；BLB 不可逆但按敏感数据对待';

-- ---------------------------------------------------------------------
-- 10. face_profile：人脸档案状态机
-- ---------------------------------------------------------------------
CREATE TABLE `face_profile` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT,
  `student_id`    BIGINT       NOT NULL,
  `status`        VARCHAR(24)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PENDING_REVIEW / ACTIVE / REJECTED',
  `sample_count`  TINYINT      NOT NULL DEFAULT 0 COMMENT '已入库样本数（5~10）',
  `submitted_at`  DATETIME(3)  NULL,
  `reviewed_by`   BIGINT       NULL,
  `reviewed_at`   DATETIME(3)  NULL,
  `reject_reason` VARCHAR(255) NULL,
  `created_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_face_profile_student` (`student_id`) COMMENT '一人一档案',
  KEY `idx_fp_status` (`status`) COMMENT '教师端「待审核」列表'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人脸档案状态机；ACTIVE 才允许签到';

-- ---------------------------------------------------------------------
-- 11. appeal：申诉
-- ---------------------------------------------------------------------
CREATE TABLE `appeal` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `attendance_id`    BIGINT       NOT NULL,
  `session_id`       BIGINT       NOT NULL COMMENT '冗余存储，避免申诉列表回表 attendance',
  `student_id`       BIGINT       NOT NULL,
  `reason`           VARCHAR(500) NOT NULL,
  `evidence_key`     VARCHAR(255) NULL COMMENT '申诉截图的加密对象键',
  `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED / WITHDRAWN',
  `reply`            VARCHAR(500) NULL,
  `handled_by`       BIGINT       NULL,
  `handled_at`       DATETIME(3)  NULL,
  `created_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  -- 同一考勤记录只允许存在一条 PENDING 申诉（FA-5001 的数据库兜底）。
  -- MySQL 没有部分索引，用生成列 + 唯一索引实现；非 PENDING 行取 NULL 不参与唯一性判断。
  `pending_attendance_id` BIGINT  GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING' THEN `attendance_id` ELSE NULL END) STORED
                                  COMMENT '仅用于 uk_appeal_pending，业务代码不得读写',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appeal_pending` (`pending_attendance_id`),
  KEY `idx_appeal_attendance` (`attendance_id`),
  KEY `idx_appeal_status` (`status`, `created_at`),
  KEY `idx_appeal_student` (`student_id`, `created_at`),
  KEY `idx_appeal_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申诉；PENDING 唯一由生成列保证';

-- ---------------------------------------------------------------------
-- 12. audit_log：审计
-- ---------------------------------------------------------------------
CREATE TABLE `audit_log` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `actor_id`    BIGINT       NULL     COMMENT '操作人 account.id；系统任务为 NULL',
  `actor_role`  VARCHAR(16)  NULL     COMMENT 'ADMIN / TEACHER / STUDENT / SYSTEM',
  `action`      VARCHAR(64)  NOT NULL COMMENT '如 CLASS_CREATE / ATTENDANCE_ADJUST / STUDENT_TRANSFER',
  `target_type` VARCHAR(32)  NULL     COMMENT '如 CLASS / COURSE / STUDENT / SESSION / ATTENDANCE',
  `target_id`   BIGINT       NULL,
  `before_json` JSON         NULL     COMMENT '变更前摘要，不含人脸数据（§13.4）',
  `after_json`  JSON         NULL     COMMENT '变更后摘要，不含人脸数据',
  `detail`      VARCHAR(500) NULL,
  `client_ip`   VARCHAR(45)  NULL     COMMENT '入库存原始 IP，展示层脱敏到 /24',
  `trace_id`    VARCHAR(32)  NULL,
  `created_at`  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_audit_actor` (`actor_id`, `created_at`),
  KEY `idx_audit_action` (`action`, `created_at`),
  KEY `idx_audit_target` (`target_type`, `target_id`),
  KEY `idx_audit_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志；仅 ADMIN 可查，保留期 ≥1 年';

-- ---------------------------------------------------------------------
-- 13. agent_query_log：Agent 问答留痕
-- ---------------------------------------------------------------------
CREATE TABLE `agent_query_log` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`         BIGINT       NOT NULL COMMENT '发起人 account.id',
  `conversation_id` VARCHAR(64)  NULL     COMMENT 'LangGraph thread_id 的会话段',
  `tool`            VARCHAR(64)  NOT NULL,
  `params_json`     JSON         NULL     COMMENT '工具入参（白名单字段，绝不含人脸数据）',
  `result_count`    INT          NULL     COMMENT '命中条数，用于回答末尾的「数据来源」',
  `duration_ms`     INT          NULL,
  `ok`              TINYINT(1)   NOT NULL DEFAULT 1,
  `error_code`      VARCHAR(16)  NULL,
  `trace_id`        VARCHAR(32)  NULL,
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_aql_user` (`user_id`, `created_at`),
  KEY `idx_aql_tool` (`tool`, `created_at`),
  KEY `idx_aql_conv` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 只读调用留痕';

-- ---------------------------------------------------------------------
-- 14. agent_action_log：Agent 写操作留痕（含两阶段确认与撤销）
-- ---------------------------------------------------------------------
CREATE TABLE `agent_action_log` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT       NOT NULL COMMENT '代为执行的用户',
  `conversation_id`   VARCHAR(64)  NULL,
  `tool`              VARCHAR(64)  NOT NULL,
  `params_json`       JSON         NULL,
  `params_hash`       CHAR(64)     NOT NULL COMMENT 'SHA-256(params)，与 confirmToken 绑定，防「确认了 A 执行了 B」',
  `token_fingerprint` CHAR(64)     NOT NULL COMMENT 'confirmToken 的 SHA-256 指纹；不存令牌原文',
  `before_json`       JSON         NULL,
  `after_json`        JSON         NULL,
  `affected_count`    INT          NOT NULL DEFAULT 0,
  `undoable`          TINYINT(1)   NOT NULL DEFAULT 0,
  `undoable_until`    DATETIME(3)  NULL     COMMENT '可撤销截止时间（默认 30 分钟）',
  `undone_at`         DATETIME(3)  NULL,
  `undone_by`         BIGINT       NULL,
  `undone_log_id`     BIGINT       NULL COMMENT '撤销动作自身也记一条日志，此处指向它',
  `trace_id`          VARCHAR(32)  NULL,
  `created_at`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_aal_user` (`user_id`, `created_at`),
  KEY `idx_aal_tool` (`tool`, `created_at`),
  KEY `idx_aal_undo` (`undoable`, `undoable_until`) COMMENT '撤销入口扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 写操作留痕；每次写操作必须有一行';

-- ---------------------------------------------------------------------
-- 15. sys_config：系统配置（阈值、开关、Agent 能力级别）
-- ---------------------------------------------------------------------
CREATE TABLE `sys_config` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `config_key`   VARCHAR(64)  NOT NULL COMMENT '如 PASS_THRESHOLD / AGENT_LEVEL_L2',
  `config_value` VARCHAR(500) NOT NULL COMMENT '一律存字符串，按 value_type 解析',
  `value_type`   VARCHAR(16)  NOT NULL DEFAULT 'STRING' COMMENT 'INT / DECIMAL / BOOL / STRING / JSON',
  `category`     VARCHAR(32)  NOT NULL DEFAULT 'FACE' COMMENT 'FACE / CHECKIN / AGENT / SYSTEM，用于配置页分组',
  `description`  VARCHAR(255) NULL,
  `editable`     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = 只读（由标定脚本写入，禁止手工改）',
  `updated_by`   BIGINT       NULL,
  `created_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_key` (`config_key`),
  KEY `idx_config_category` (`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置；阈值优先读库，缺省回落环境变量';
