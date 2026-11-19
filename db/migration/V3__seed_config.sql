-- ---------------------------------------------------------------------
-- V3：sys_config 种子数据
--
-- 为什么要有这一版：`sys_config` 在 V1 只建了表，没有初始行。
-- 后果是管理端「系统配置」页一片空白，`AgentLevelService` 也只能吃默认值——
-- 默认值能跑，但管理员看不到、改不了，等于开关是个摆设。
--
-- 取值来源：项目文档 §12.3 环境变量清单（PASS_THRESHOLD=0.45 等）。
-- editable=0 的项由标定脚本维护：阈值改动必须留标定依据，
-- 不能让人在页面上手滑改掉（否则 FAR/FRR 的结论就不可复现了）。
-- ---------------------------------------------------------------------

-- 用 INSERT IGNORE 而不是 ON DUPLICATE KEY UPDATE ... AS new：
-- 后者要求 MySQL 8.0.19+，而 CI 的镜像版本不受我们控制。
-- 这里语义上也只需要「没有就补一条」，不需要覆盖已有人工调整。
INSERT IGNORE INTO `sys_config`
  (`config_key`, `config_value`, `value_type`, `category`, `description`, `editable`, `updated_by`)
VALUES
  -- 人脸：标定脚本写入，管理端只读
  ('PASS_THRESHOLD',    '0.45', 'DECIMAL', 'FACE',    '1:N 比对通过阈值（CASIA 标定，勿手改）', 0, NULL),
  ('MARGIN_THRESHOLD',  '0.06', 'DECIMAL', 'FACE',    'top1 与 top2 的最小差值（防同脸双档）',   0, NULL),
  ('LIVENESS_THRESHOLD','0.55', 'DECIMAL', 'FACE',    '活体分数阈值（多帧中位数）',              0, NULL),
  ('IDENTITY_SIM',      '0.55', 'DECIMAL', 'FACE',    '人脸与学籍身份的相似度下限',              0, NULL),
  ('MOUTH_DARK_ABS',    '0.05', 'DECIMAL', 'FACE',    '张嘴动作的暗区面积下限',                  0, NULL),
  -- 人脸：可调
  ('MULTI_FACE_MIN_PX', '90',   'INT',     'FACE',    '小于该像素的人脸不计入「多人」判定',      1, NULL),
  ('FACE_WORKERS',      '4',    'INT',     'FACE',    '人脸服务的工作进程数',                    1, NULL),
  -- 签到
  ('CHECKIN_MAX_RETRY', '5',    'INT',     'CHECKIN', '单场签到允许的失败重试次数（FA-3005）',   1, NULL),
  ('CHECKIN_COOLDOWN_SEC', '6', 'INT',     'CHECKIN', '签到成功后的冷却秒数（防连点）',          1, NULL),
  -- Agent
  ('AGENT_LEVEL_L0',    'true', 'BOOL',    'AGENT',   'L0 只读查询工具总开关',                   1, NULL),
  ('AGENT_LEVEL_L1',    'true', 'BOOL',    'AGENT',   'L1 草稿工具（不落库）总开关',             1, NULL),
  ('AGENT_LEVEL_L2',    'true', 'BOOL',    'AGENT',   'L2 需确认的写操作总开关',                 1, NULL),
  ('AGENT_LEVEL_L3',    'false','BOOL',    'AGENT',   'L3 定时批量：默认关闭，需管理员显式开启', 1, NULL),
  ('CONFIRM_TTL_SEC',   '60',   'INT',     'AGENT',   '写操作确认令牌有效期（秒）',              1, NULL),
  ('UNDO_WINDOW_SEC',   '1800', 'INT',     'AGENT',   '撤销窗口（秒）',                          1, NULL),
  -- 系统
  ('APPEAL_TIMEOUT_HOURS', '48','INT',     'SYSTEM',  '申诉超过该时长未处理即提醒',              1, NULL);
