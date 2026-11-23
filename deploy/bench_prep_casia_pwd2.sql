-- bench5000 前置补充：CASIAT 班其余 ACTIVE 学生（C12..C99）密码统一
-- 备份追加到 bench_casia_pwd_backup（已有 C0..C11）
INSERT INTO bench_casia_pwd_backup (id, username, password_hash, credential_version)
SELECT a.id, a.username, a.password_hash, a.credential_version
FROM account a
WHERE a.username IN ('C12','C13','C14','C15','C16','C19','C20','C22','C24','C25',
                     'C26','C27','C31','C33','C34','C35','C36','C37','C43','C44',
                     'C46','C48','C49','C51','C55','C56','C58','C59','C60','C61',
                     'C63','C64','C70','C71','C73','C76','C77','C78','C79','C80',
                     'C81','C82','C83','C84','C86','C88','C89','C91','C92','C93',
                     'C94','C96','C98','C99')
  AND NOT EXISTS (SELECT 1 FROM bench_casia_pwd_backup b WHERE b.id = a.id);

UPDATE account a
JOIN (SELECT password_hash pw FROM account WHERE username = 'LD80c3c0S1' LIMIT 1) h
  ON a.username IN ('C12','C13','C14','C15','C16','C19','C20','C22','C24','C25',
                    'C26','C27','C31','C33','C34','C35','C36','C37','C43','C44',
                    'C46','C48','C49','C51','C55','C56','C58','C59','C60','C61',
                    'C63','C64','C70','C71','C73','C76','C77','C78','C79','C80',
                    'C81','C82','C83','C84','C86','C88','C89','C91','C92','C93',
                    'C94','C96','C98','C99')
SET a.password_hash = h.pw;

SELECT COUNT(*) AS backed_up FROM bench_casia_pwd_backup;
