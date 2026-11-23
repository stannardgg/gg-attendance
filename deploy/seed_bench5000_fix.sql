-- 修复负载向量副本污染：负载学生（B5K）持有压测池学生（CASIAT 14 人）的
-- 向量精确副本 → top2 相似度 ≈ top1 → margin<0.06 → match 门禁误拒。
-- 处理：删除池身份的副本行（source_ref='stress-seed'），用非 CASIAT 班
-- （LD/e2e 遗留学生）的向量轮转补齐缺口（stress-seed2）。
-- 注：非池 CASIAT 学生与池学生身份不重叠（一人一身份），其副本不影响 margin。

DELETE fv
FROM face_vector fv
JOIN student s ON s.id = fv.student_id AND s.student_no LIKE 'B5K%'
JOIN student pool ON pool.student_no IN
  ('C3','C10','C11','C12','C16','C27','C35','C60','C61','C71','C86','C88','C91','C98')
JOIN face_vector pv ON pv.student_id = pool.id AND fv.embedding = pv.embedding;

INSERT INTO face_vector (student_id, model_name, dim, embedding, quality_score, yaw, pitch, brightness, source_ref)
SELECT s.id, v.model_name, v.dim, v.embedding, v.quality_score, v.yaw, v.pitch, v.brightness, 'stress-seed2'
FROM student s
JOIN (
  SELECT fv.model_name, fv.dim, fv.embedding, fv.quality_score, fv.yaw, fv.pitch, fv.brightness,
         ROW_NUMBER() OVER (ORDER BY fv.id) AS rn,
         COUNT(*) OVER () AS total
  FROM face_vector fv
  JOIN student os ON os.id = fv.student_id
  WHERE os.class_id <> 29
    AND os.student_no NOT LIKE 'B5K%'
) v ON ((s.id % v.total) + 1) = v.rn
WHERE s.student_no LIKE 'B5K%'
  AND NOT EXISTS (
    SELECT 1 FROM face_vector f2
    WHERE f2.student_id = s.id AND f2.source_ref = 'stress-seed2')
LIMIT 400;

SELECT
  (SELECT COUNT(*) FROM face_vector) total_vectors,
  (SELECT COUNT(*) FROM face_vector f JOIN student s ON s.id = f.student_id
    WHERE s.student_no LIKE 'B5K%') b5k_vectors,
  (SELECT COUNT(*) FROM face_vector f JOIN student s ON s.id = f.student_id
    WHERE s.student_no LIKE 'B5K%' AND f.source_ref = 'stress-seed2') b5k_refilled;
