-- ---------------------------------------------------------------------
-- V6：教师的登录用户名与密码也走「申请 → 审核」（CR-022，扩展 CR-019）
--
-- 背景：V5 把姓名/院系/职称变成了申请制，但登录名（工号）与密码仍只有管理员
-- 能改 —— 教师忘了密码只能线下找人。本迁移让「账号凭据」也进同一张申请表，
-- 保持一条链：教师提交 → 管理员审核 → 通过才生效。
--
-- 四个刻意的设计：
--   1. 密码只存 BCrypt 哈希，明文永不落库、不进日志、不做审计快照。
--      提交时就哈希（而不是审核时），明文只存在于那一次请求的内存里。
--   2. password_change 是稳定标记，new_password_hash 只是暂存：
--      审核通过（已写入 account）或驳回/撤回后立即把哈希清空，
--      避免一串无人认领的凭据长期躺在业务表里。
--   3. account.credential_version 是「凭据版本」。改密或改登录名时 +1，
--      令牌里带 cv；版本不符即视为失效。这样不需要依赖 Redis 黑名单
--      就能把该账号已签发的所有令牌一次性作废 —— 改密后旧会话必须重登。
--   4. 登录名双写 account.username 与 teacher.teacher_no，审核通过时在同一个
--      事务里改两处；提交时先查一次唯一性（早报错），审核时再查一次
--      （防止提交到审批之间被别人占用）。
-- ---------------------------------------------------------------------

ALTER TABLE `account`
  ADD COLUMN `credential_version` INT NOT NULL DEFAULT 0
    COMMENT '凭据版本：改密或改登录名后 +1，令牌 cv 不匹配即失效'
    AFTER `status`;

ALTER TABLE `teacher_profile_request`
  ADD COLUMN `old_username`      VARCHAR(32)  NULL COMMENT '提交时刻的登录用户名快照' AFTER `old_title`,
  ADD COLUMN `new_username`      VARCHAR(32)  NULL COMMENT '申请的新登录用户名；NULL 表示不申请' AFTER `new_title`,
  ADD COLUMN `password_change`   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = 本次申请重置密码' AFTER `new_username`,
  ADD COLUMN `new_password_hash` VARCHAR(100) NULL COMMENT '新密码的 BCrypt 哈希，仅审核前暂存，事后立即清空' AFTER `password_change`;

ALTER TABLE `teacher_profile_request`
  COMMENT = '教师账号与资料修改申请；PENDING 唯一由生成列保证';
