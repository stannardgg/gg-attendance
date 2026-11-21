-- ---------------------------------------------------------------------
-- V4：申诉 PENDING 唯一性改为「同一学生 + 同一场次」
--
-- 背景（CR-015）：会话进行中即可申诉，此时还没有 attendance 行，
-- 申诉以 attendance_id = 0 落库（见 AppealService.create）。
-- 原 uk_appeal_pending 建在 attendance_id 上，于是全库同时只允许存在
-- **一条**「无记录」的 PENDING 申诉：第二个学生提交进行中申诉会被
-- 数据库误判成 FA-5001「该记录已有待处理申诉」，第一个学生撤回/通过后才放行。
--
-- 业务口径本来就是「同一学生同一场次只能有一条在办申诉」，与 attendance
-- 行是否存在无关。改用 (session_id, student_id) 的组合生成键。
--
-- 兼容性：已有数据里同场同人可能存在两条 PENDING（旧键只看 attendance_id），
-- 故先把多余的那条置为 WITHDRAWN（保留 id 最小的一条），再加唯一索引。
-- ---------------------------------------------------------------------

UPDATE `appeal` a
  JOIN (
      SELECT `session_id`, `student_id`, MIN(`id`) AS keep_id
        FROM `appeal`
       WHERE `status` = 'PENDING'
       GROUP BY `session_id`, `student_id`
      HAVING COUNT(*) > 1
  ) d ON d.`session_id` = a.`session_id` AND d.`student_id` = a.`student_id`
   SET a.`status`     = 'WITHDRAWN',
       a.`reply`      = '系统合并重复申诉（V4 迁移）',
       a.`handled_at` = NOW(3)
 WHERE a.`status` = 'PENDING'
   AND a.`id` <> d.keep_id;

ALTER TABLE `appeal`
  DROP INDEX `uk_appeal_pending`,
  DROP COLUMN `pending_attendance_id`,
  ADD COLUMN `pending_session_student` VARCHAR(48)
      GENERATED ALWAYS AS (
          CASE WHEN `status` = 'PENDING'
               THEN CONCAT(`session_id`, ':', `student_id`)
          END) STORED
      COMMENT '仅用于 uk_appeal_pending，业务代码不得读写',
  ADD UNIQUE KEY `uk_appeal_pending` (`pending_session_student`);
