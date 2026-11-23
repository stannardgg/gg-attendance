-- bench5000 前置：CASIAT 班 C0..C11 密码统一（先备份，压测后回滚）
-- 备份表：bench_casia_pwd_backup（压测完 DROP）
DROP TABLE IF EXISTS bench_casia_pwd_backup;
CREATE TABLE bench_casia_pwd_backup AS
SELECT a.id, a.username, a.password_hash, a.credential_version
FROM account a
WHERE a.username IN ('C0','C1','C2','C3','C4','C5','C6','C7','C8','C9','C10','C11');

UPDATE account a
JOIN (SELECT password_hash pw FROM account WHERE username = 'LD80c3c0S1' LIMIT 1) h
  ON a.username IN ('C0','C1','C2','C3','C4','C5','C6','C7','C8','C9','C10','C11')
SET a.password_hash = h.pw;

SELECT username, credential_version FROM account
WHERE username IN ('C0','C1','C2','C3','C4','C5','C6','C7','C8','C9','C10','C11');
