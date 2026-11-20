# CR-012 · 人脸向量字段级加密与备份加密（上线前安全加固）

- 提出人：主人（2026-11-20 会话内确认实施方案）
- 提出日期：2026-11-20
- 状态：✅ **已批准并实现**
- 关联：§13.3 存储 / §13.4 最小必要 / R9 人脸数据合规风险

## 1. 问题

v1.0.2 上线后的数据盘点（2026-11-20 实测代码库）：

1. **人脸原图从未落盘**：§13.3 原文写的「人脸原图 AES-256 加密后存 MinIO」对应的是
   一条已被更优方案替代的链路——实际实现里原图只在 face 服务内存中解码、提特征后
   即丢弃，MinIO 在 compose 里挂在 `profiles:["archive"]` 下且代码零引用。
   落库的敏感数据只有 `face_vector.embedding`（512 维 float32 BLOB）。
2. **向量在库里是明文**：PIPL 口径下人脸向量属敏感个人信息，且存在模型反演风险，
   不能因"不可逆推出原图"就豁免加密。数据库文件、宿主机磁盘、误备份都是明文暴露面。
3. **备份是明文**：`backup.sh` 产出的 mysqldump 含全量向量——库加密了而备份不加密
   等于没加密。
4. `face_vector.source_ref` 存的是原图 sha256 前 16 位（当初的 Redis key），
   原图本就不持久化，属悬空引用（无害，本文档记录口径，V1 DDL 注释不改——已冻结）。

## 2. 方案

### 2.1 向量字段级加密（AES-256-GCM，应用层）

- 密文布局：`版本(1B) || IV(12B 随机) || GCM 密文(含 16B 认证 tag)`，
  2048B 明文 → 2077B 密文，BLOB 容量与性能无感（单条加解密微秒级）。
- 密钥：`FA_VECTOR_AES_KEY`（32 字节 key 的 base64，`openssl rand -base64 32` 生成），
  经 compose 注入 java 容器，不入库不入 git。未配置 = 明文落库 + 启动 WARN
  （本地/CI 不受阻），配错长度/格式拒绝启动。
- 为什么不用 MySQL TDE：社区版没有 TDE，企业版付费。
- 为什么不用 MyBatis TypeHandler：embedding 有两条访问路径——MyBatis-Plus 写入
  （`FaceEnrollService`）与 JdbcTemplate 读取（`InternalVectorController`），
  TypeHandler 只能覆盖前者；两条路径共用 `VectorCrypto` 组件才不会漏。
- 存量兼容：解密按「首字节 == 0x01 且总长 == 明文长 + 29」双硬条件判定密文，
  明文行原样返回，**无需停机迁移**；存量向量在该学生下次整批重采时自然被加密替换。
  单靠首字节判定会有约 1/256 误判（float32 首字节碰巧等于版本号），长度条件消除歧义。
- 轮换：换 Key 前先跑完旧数据的重加密（可按版本字节渐进识别），现阶段数据量小
  （千级学生 × 5~10 样本），一次性批处理即可；规模扩大后再考虑信封加密 + KMS。

### 2.2 备份加密

- `backup.sh`：dump 先做完整性校验（gzip -t + Dump completed 尾标记），通过后
  `openssl enc -aes-256-cbc -pbkdf2 -iter 600000` 整体加密，明文 `.sql.gz` 立即删除，
  指纹（.sha256）作用在最终产物上。`BACKUP_AES_KEY` 未配置保持旧行为 + WARN。
- `restore.sh`：按 `.enc` 后缀自动解密；口令错在 openssl 阶段报 bad decrypt 退出，
  不会污染目标库。
- `BACKUP_AES_KEY` 与 `FA_VECTOR_AES_KEY` 必须是不同的值（备份与库不共享密钥）。

## 3. 影响面

| 位置 | 变更 |
|---|---|
| `fa-org/infra/VectorCrypto.java`（新增） | 加解密组件，构造时校验密钥，未配置 WARN |
| `fa-org/app/FaceEnrollService.java` | 落库前 `encrypt(embedding)` |
| `fa-org/api/InternalVectorController.java` | 快照出库 `decrypt(stored, dim*4)`——**解密边界**，Python 协议不变 |
| `fa-org/src/test/.../VectorCryptoTest.java`（新增） | 9 个纯逻辑用例（往返/篡改/换钥/存量兼容/无钥/非法密钥） |
| `deploy/docker-compose.yml` | java 服务 environment 增加 `FA_VECTOR_AES_KEY` |
| `deploy/.env.example` / `deploy/.env` | 新增 `FA_VECTOR_AES_KEY`、`BACKUP_AES_KEY` |
| `deploy/scripts/backup.sh` / `restore.sh` | 加密 / 解密支持 |
| `项目文档.md` §13.3 | 口径更新：原图不落盘，MinIO 仅在启用"人工复核看原图"时落地 |

## 4. 上线检查清单

1. `deploy/.env` 已配置 `FA_VECTOR_AES_KEY` 与 `BACKUP_AES_KEY`（两值不同）。
2. java 容器重建（`docker compose up -d java`，restart 不刷新 env）后日志出现
   「人脸向量字段级加密已启用」。
3. 新采集的向量在库里首字节为 0x01、总长 2077B；`/internal/face/vectors` 快照
   正常返回明文 base64（Python 索引无感）。
4. `backup.sh` 产出 `.sql.gz.enc`；`restore.sh` 对它演练 GATE PASS。

## 5. 裁决记录

| 日期 | 裁决人 | 结论 | 落地动作 |
|---|---|---|---|
| 2026-11-20 | 主人 | ✅ 批准（会话内确认"确认"） | 按 §2 实现 |
