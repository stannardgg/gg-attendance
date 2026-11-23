-- =====================================================================
-- 5000+ 学生压测造数（bench5000）
-- 前缀约定：B5K*（学生账号/学号）、STRESS5K*（班/课/教师）——回滚只删这些前缀
-- 原则：纯 INSERT，不改/不删任何现有行（对基线数据零影响）
-- 执行：docker exec -i fa-mysql-1 sh -c 'MYSQL_PWD=xxx mysql -uroot face_attendance' < seed_bench5000.sql
-- =====================================================================

SET SESSION cte_max_recursion_depth = 6000;

-- 0. 幂等护栏：已造过则直接退出（information_schema 判学号前缀）
SELECT IF(
  (SELECT COUNT(*) FROM student WHERE student_no LIKE 'B5K%') > 0,
  'ALREADY_SEEDED_SKIP',
  'PROCEED') AS guard;

-- 1. 压测大班（5000 人一个班 = 极限场景）
INSERT INTO class_group (code, name)
SELECT 'STRESS5K', CONCAT('压测大班', '5000')
WHERE NOT EXISTS (SELECT 1 FROM class_group WHERE code = 'STRESS5K');

SET @cid = (SELECT id FROM class_group WHERE code = 'STRESS5K');

-- 2. 压测课程
INSERT INTO course (code, name)
SELECT 'STRESS5K', '压测课程5000'
WHERE NOT EXISTS (SELECT 1 FROM course WHERE code = 'STRESS5K');

SET @kid = (SELECT id FROM course WHERE code = 'STRESS5K');

-- 3. 压测教师（复用 LDT* 教师的 password_hash，由 e2e flows 统一创建，密码已知）
INSERT INTO account (username, password_hash, role, status)
SELECT 'STRESS_T', h.pw, 'TEACHER', 'ACTIVE'
FROM (SELECT password_hash pw FROM account
      WHERE username = 'LDTd19389' LIMIT 1) h
WHERE NOT EXISTS (SELECT 1 FROM account WHERE username = 'STRESS_T');

INSERT INTO teacher (teacher_no, name, account_id)
SELECT 'STRESS_T', '压测教师', a.id
FROM account a
WHERE a.username = 'STRESS_T'
  AND NOT EXISTS (SELECT 1 FROM teacher t WHERE t.teacher_no = 'STRESS_T');

SET @tid = (SELECT id FROM teacher WHERE teacher_no = 'STRESS_T');

INSERT INTO teacher_scope (teacher_id, scope_type, scope_id)
SELECT @tid, 'CLASS', @cid
WHERE NOT EXISTS (SELECT 1 FROM teacher_scope
                  WHERE teacher_id = @tid AND scope_type = 'CLASS' AND scope_id = @cid);

INSERT INTO teacher_scope (teacher_id, scope_type, scope_id)
SELECT @tid, 'COURSE', @kid
WHERE NOT EXISTS (SELECT 1 FROM teacher_scope
                  WHERE teacher_id = @tid AND scope_type = 'COURSE' AND scope_id = @kid);

-- 4. 5000 个学生账号（复用 LD* 学生的 password_hash，由 e2e flows 统一创建，密码已知）
INSERT INTO account (username, password_hash, role, status)
SELECT CONCAT('B5K', LPAD(n, 4, '0')), h.pw, 'STUDENT', 'ACTIVE'
FROM (
  WITH RECURSIVE seq(n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5000
  ) SELECT n FROM seq
) s
CROSS JOIN (
  SELECT password_hash pw FROM account
  WHERE username = 'LD80c3c0S1' LIMIT 1
) h
WHERE NOT EXISTS (
  SELECT 1 FROM account a
  WHERE a.username = CONCAT('B5K', LPAD(s.n, 4, '0')));

-- 5. 学生档案（全部挂到压测大班）
INSERT INTO student (account_id, student_no, name, class_id, status)
SELECT a.id, a.username, CONCAT('压测学生', LPAD(
         ROW_NUMBER() OVER (ORDER BY a.username), 4, '0')), @cid, 'ACTIVE'
FROM account a
WHERE a.username LIKE 'B5K%' AND a.role = 'STUDENT'
  AND NOT EXISTS (SELECT 1 FROM student s WHERE s.student_no = a.username);

-- 6. 人脸档案（ACTIVE：满足 join 硬性门禁，检索负载真实存在）
INSERT INTO face_profile (student_id, status, sample_count, submitted_at, reviewed_by, reviewed_at)
SELECT s.id, 'ACTIVE', 6, NOW(3), 1, NOW(3)
FROM student s
WHERE s.student_no LIKE 'B5K%'
  AND NOT EXISTS (SELECT 1 FROM face_profile fp WHERE fp.student_id = s.id);

-- 7. 5000 条人脸向量（轮转复制现有 ArcFace 向量 blob，512 维 f32 原样）
--    用途：把 1:N 检索负载从 ~1.1k 向量放大到 ~6.1k 向量；
--    这些学生不参与签到压测（同源复制向量 margin≈0 会被 match 门禁判 REVIEW/失败，
--    不影响检索计算成本的真实性——点积成本只与向量条数相关）
INSERT INTO face_vector (student_id, model_name, dim, embedding, quality_score, yaw, pitch, brightness, source_ref)
SELECT s.id, v.model_name, v.dim, v.embedding, v.quality_score, v.yaw, v.pitch, v.brightness, 'stress-seed'
FROM student s
JOIN (
  SELECT fv.model_name, fv.dim, fv.embedding, fv.quality_score, fv.yaw, fv.pitch, fv.brightness,
         ROW_NUMBER() OVER (ORDER BY fv.id) AS rn,
         COUNT(*) OVER () AS total
  FROM face_vector fv
) v ON ((s.id % v.total) + 1) = v.rn
WHERE s.student_no LIKE 'B5K%';

-- 8. 核对
SELECT
  (SELECT COUNT(*) FROM student WHERE student_no LIKE 'B5K%')          AS b5k_students,
  (SELECT COUNT(*) FROM account WHERE username LIKE 'B5K%')            AS b5k_accounts,
  (SELECT COUNT(*) FROM face_profile fp JOIN student s ON s.id = fp.student_id
        WHERE s.student_no LIKE 'B5K%' AND fp.status = 'ACTIVE')        AS b5k_active_profiles,
  (SELECT COUNT(*) FROM face_vector fv JOIN student s ON s.id = fv.student_id
        WHERE s.student_no LIKE 'B5K%')                                 AS b5k_vectors,
  (SELECT COUNT(*) FROM face_vector)                                    AS total_vectors,
  (SELECT COUNT(*) FROM student)                                        AS total_students;
