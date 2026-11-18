-- =====================================================================
-- 演示数据（seed）· 仅供开发与联调环境
-- =====================================================================
-- 归属工作包：WP-01（B1）
-- 事实来源  ：项目文档.md §14.3 WP-01「seed.sql（1 个 ADMIN + 1 个演示教师
--            + 1 个班 + 3 学生 + 1 课程）」
--
-- ★ 生产环境不得执行本文件。
--   生产的管理员由 Java 启动时的 BOOTSTRAP_ADMIN_* 环境变量创建（§9.3），
--   演示班级/课程/学生一律通过管理端录入。
--
-- 幂等性：全部使用固定主键 + INSERT IGNORE / ON DUPLICATE KEY UPDATE，
--         重复执行不会产生重复行，也不会覆盖已修改的演示数据（除 sys_config）。
--
-- 演示账号与密码（BCrypt strength=10）：
--   admin   / Admin@12345     角色 ADMIN
--   T2024001/ Teacher@12345   角色 TEACHER
--   S2024001/ Student@12345   角色 STUDENT
--   S2024002/ Student@12345   角色 STUDENT
--   S2024003/ Student@12345   角色 STUDENT
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- 1. 账号（3 类角色各若干）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `account` (`id`, `username`, `password_hash`, `role`, `status`, `face_consent_at`, `face_consent_version`) VALUES
  (1, 'admin',    '$2y$10$SoXL/wRElIRVvLy9UhDAu.eu9fkn8vFeb8gKtaQpnB4jDTiQxbhlG', 'ADMIN',   'ACTIVE', NULL, NULL),
  (2, 'T2024001', '$2y$10$pueeO9/3.T98shTFxQPmQO9V6xnWbCZldA5dMrnMLW2wmuigdnYNC', 'TEACHER', 'ACTIVE', NULL, NULL),
  (3, 'S2024001', '$2y$10$ojOJZ5AKjVxL3Cs/ZD9la.15No/MtcgJRwYIgX.qKGiCHnkJUOctC', 'STUDENT', 'ACTIVE', '2026-11-01 09:00:00.000', 'v1'),
  (4, 'S2024002', '$2y$10$ojOJZ5AKjVxL3Cs/ZD9la.15No/MtcgJRwYIgX.qKGiCHnkJUOctC', 'STUDENT', 'ACTIVE', '2026-11-01 09:00:00.000', 'v1'),
  (5, 'S2024003', '$2y$10$ojOJZ5AKjVxL3Cs/ZD9la.15No/MtcgJRwYIgX.qKGiCHnkJUOctC', 'STUDENT', 'ACTIVE', '2026-11-01 09:00:00.000', 'v1');

-- ---------------------------------------------------------------------
-- 2. 教师档案
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `teacher` (`id`, `account_id`, `teacher_no`, `name`, `department`, `title`, `status`) VALUES
  (1, 2, 'T2024001', '李国强', '计算机科学与技术学院', '副教授', 'ACTIVE');

-- ---------------------------------------------------------------------
-- 3. 班级（独立主数据，不含任何课程字段）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `class_group` (`id`, `code`, `name`, `grade`, `major`, `owner_teacher_id`, `status`, `remark`) VALUES
  (1, 'CS2024', '计算机 2024 级 1 班', '2024', '计算机科学与技术', 1, 'ACTIVE', '演示班级');

-- ---------------------------------------------------------------------
-- 4. 课程（独立主数据，不含任何班级字段）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `course` (`id`, `code`, `name`, `semester`, `owner_teacher_id`, `status`, `remark`) VALUES
  (1, 'MATH101', '高等数学（上）', '2026-2027-1', 1, 'ACTIVE', '演示课程');

-- ---------------------------------------------------------------------
-- 5. 学生（归属班级 1）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `student` (`id`, `account_id`, `student_no`, `name`, `class_id`, `status`) VALUES
  (1, 3, 'S2024001', '张三', 1, 'ACTIVE'),
  (2, 4, 'S2024002', '李四', 1, 'ACTIVE'),
  (3, 5, 'S2024003', '王五', 1, 'ACTIVE');

-- ---------------------------------------------------------------------
-- 6. 人脸档案（初始均为 NONE，采集后转 PENDING_REVIEW）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `face_profile` (`id`, `student_id`, `status`, `sample_count`) VALUES
  (1, 1, 'NONE', 0),
  (2, 2, 'NONE', 0),
  (3, 3, 'NONE', 0);

-- ---------------------------------------------------------------------
-- 7. 教师授权范围（★ 班级与课程分别授权，两者互不引用）
--    没有这两行，演示教师连自己的班都查不到（§3.1 Data Scope）
-- ---------------------------------------------------------------------
INSERT IGNORE INTO `teacher_scope` (`id`, `teacher_id`, `scope_type`, `scope_id`, `granted_by`) VALUES
  (1, 1, 'CLASS',  1, 1),
  (2, 1, 'COURSE', 1, 1);

-- ---------------------------------------------------------------------
-- 8. 系统配置（阈值默认值，来自 §10.5 与 §11.8）
--    editable=0 的项由 calibrate.py 写入，管理端只读（§5.4 阈值必须标定）
-- ---------------------------------------------------------------------
INSERT INTO `sys_config` (`config_key`, `config_value`, `value_type`, `category`, `description`, `editable`) VALUES
  ('PASS_THRESHOLD',        '0.45',  'DECIMAL', 'FACE',    '1:N 比对通过阈值，须用 calibrate.py 标定', 1),
  ('MARGIN_THRESHOLD',      '0.06',  'DECIMAL', 'FACE',    'top1 与 top2 的最小差距', 1),
  ('REVIEW_THRESHOLD',      '0.38',  'DECIMAL', 'FACE',    '0.38~0.45 判为 REVIEW（识别不够确定）', 1),
  ('LIVENESS_THRESHOLD',    '0.55',  'DECIMAL', 'FACE',    '门禁1：多帧活体概率中位数下限', 1),
  ('LIVENESS_SAMPLES',      '5',     'INT',     'FACE',    '门禁1 抽样帧数', 1),
  ('IDENTITY_SIM',          '0.55',  'DECIMAL', 'FACE',    '门禁2：首末帧余弦相似度下限', 1),
  ('LIVENESS_BACKEND',      'basic', 'STRING',  'FACE',    '活体后端；接入 ONNX 模型后改为 model', 0),
  ('LIVENESS_LIVE_INDEX',   '1',     'INT',     'FACE',    '真人概率在模型输出中的索引，换模型必须复核', 0),
  ('LIVENESS_INPUT_SCALE',  '255.0', 'DECIMAL', 'FACE',    '模型输入标定尺度，换模型必须复核（喂 [0,1] 会静默失效）', 0),
  ('FACE_MIN_SHORT_SIDE',   '120',   'INT',     'FACE',    '采集门限：人脸短边最小像素', 1),
  ('FACE_QUALITY_LAPLACIAN','80',    'INT',     'FACE',    '采集门限：Laplacian 方差下限', 1),
  ('FACE_BRIGHTNESS_MIN',   '60',    'INT',     'FACE',    '采集门限：灰度均值下限', 1),
  ('FACE_BRIGHTNESS_MAX',   '190',   'INT',     'FACE',    '采集门限：灰度均值上限', 1),
  ('FACE_DUP_SIM',          '0.95',  'DECIMAL', 'FACE',    '入库去重阈值', 1),
  ('ACTION_WHITELIST',      'MOUTH_ONCE,MOUTH_TWICE', 'STRING', 'FACE', '动作可测性白名单', 0),
  ('MOUTH_DARK_ABS',        '0.05',  'DECIMAL', 'FACE',    '嘴区暗像素占比相对基线的绝对增幅', 1),
  ('MOUTH_RISE_RATIO',      '1.45',  'DECIMAL', 'FACE',    '峰值/基线 的倍数', 1),
  ('MOUTH_HOLD_FRAMES',     '2',     'INT',     'FACE',    '一次张嘴至少连续几帧才算「张开了一次」', 1),
  ('MULTI_FACE_FRAMES',     '3',     'INT',     'CHECKIN', '连续几帧多脸才中止', 1),
  ('MULTI_FACE_MIN_PX',     '90',    'INT',     'CHECKIN', '小于此宽度的脸不计入多脸拦截', 1),
  ('ACTION_WINDOW_MS',      '2000',  'INT',     'CHECKIN', 'MOUTH_ONCE 动作窗口', 1),
  ('ACTION_WINDOW_MS_TWICE','3000',  'INT',     'CHECKIN', 'MOUTH_TWICE 动作窗口', 1),
  ('MIN_FRAMES',            '8',     'INT',     'CHECKIN', '窗口内最少帧数，不足不判失败', 1),
  ('CHALLENGE_TTL_SEC',     '60',    'INT',     'CHECKIN', '挑战一次性有效期', 1),
  ('MAX_RETRY_PER_SESSION', '3',     'INT',     'CHECKIN', '每会话每人最多重试次数', 1),
  ('ABSENT_GRACE_SEC',      '0',     'INT',     'CHECKIN', '关闭后多久补 ABSENT（0=立即）', 1),
  ('AGENT_LEVEL_L0',        'true',  'BOOL',    'AGENT',   'L0 只读查询', 1),
  ('AGENT_LEVEL_L1',        'true',  'BOOL',    'AGENT',   'L1 生成草稿', 1),
  ('AGENT_LEVEL_L2',        'true',  'BOOL',    'AGENT',   'L2 写操作（仍需逐次确认）', 1),
  ('AGENT_LEVEL_L3',        'false', 'BOOL',    'AGENT',   'L3 定时与批量（默认关闭，需管理员授权）', 1),
  ('CONFIRM_TTL_SEC',       '60',    'INT',     'AGENT',   'confirmToken 有效期', 1),
  ('UNDO_WINDOW_SEC',       '1800',  'INT',     'AGENT',   '写操作可撤销窗口', 1),
  ('AGENT_MAX_STEPS',       '4',     'INT',     'AGENT',   '单次问答最多工具步数', 1)
ON DUPLICATE KEY UPDATE
  `description` = VALUES(`description`),
  `value_type`  = VALUES(`value_type`),
  `category`    = VALUES(`category`);
