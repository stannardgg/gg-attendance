# 人脸签到系统 · 5000+ 学生规模压测报告

- **日期**：2026-11-23
- **环境**：单机 Docker Compose 全栈（deploy/），macOS / 8 核 CPU / 16GB 内存
  - Java：fa-java-1（1.0GiB RSS）｜人脸服务：fa-face-1（1.9GiB RSS，**FACE_WORKERS=2**）
  - MySQL 8.0（max_connections=300）｜Redis 7｜入口 nginx，`CHALLENGE_TTL_SEC=60`（契约值，未放宽）
- **压测工具**：`tests/e2e/bench5000.py`（登录/读路径/大班/导出/签到全链路）、`bench_pool.py`（串行筛选）、`bench_solo.py`（串行对照）；原始 JSON 见 `docs/benchmark/data/`
- **口径**：签到延迟 = 单条端到端（join → 挑战 start → 8 帧 → finish 落库）；perFrame = 每帧上传往返（含检测 + 活体特征 + ArcFace 提取 + 1:N 检索）；只统计「链路走完」的样本算延迟，失败样本单独归因

---

## 1. 数据规模

| 指标 | 压测前（基线） | 压测后 |
|---|---|---|
| 学生总数 | 307 | **5307**（新增 B5K 压测学生 5000） |
| 账号总数 | 388 | 5389 |
| face_profile ACTIVE | 218 | 5218 |
| 人脸向量（1:N 检索库） | 1086 | **6171**（5.7 倍） |
| STRESS5K 大班 | — | 5000 人（单班） |
| 最大单场应到（session_expected） | ~157 | **5000** |

造数方式：SQL 直插（`deploy/seed_bench5000.sql`），向量轮转复制现有 ArcFace blob（512 维 f32）——检索计算成本只与向量条数相关，副本不影响延迟数据的真实性。

## 2. 结果矩阵

### 2.1 人脸签到全链路（核心瓶颈场景）

| 并发 | 库规模 | 成功率 | 端到端 P50 | 每帧 P50 | finish P50 | 吞吐（条/s） | 主要失败归因 |
|---|---|---|---|---|---|---|---|
| 串行（14 人逐个） | 1086 向量 | 100%* | — | ~0.3s（推算） | — | 0.39 | — |
| 串行（14 人逐个） | 6166 向量 | 100%* | — | — | — | 0.40（148.2s/14 人） | — |
| 8 并发 | 6166 向量 | **8/8 = 100%** | 25.5s | 2.35s | 1.47s | 0.31 | 无 |
| 14 并发 | 6166 向量 | **14/14 = 100%** | 40.0s | 3.63s | 1.80s | 0.33 | 无 |
| 30 并发 | 6166 向量 | 2/30 = 6.7% | 70.8s | 8.55s | — | 0.03 | **FA-3002 挑战超时（TTL 60s）** |
| 30 并发 | 1086 向量 | 3/30 | 38.3s | 3.25s | 3.40s | 0.31 | 挑战超时 + 素材活体分 |

\* 串行/并发签到池为 14 名经串行筛选的学生（CASIA 合成素材活体分达标者，60 名候选中 14 名通过，与库规模无关，详见 §3.3）。

**容量结论：在 FACE_WORKERS=2、挑战 TTL=60s 的生产配置下，系统可用并发 ≈ 14 条签到流水；30 并发连发（压测机 0 帧间隔的最坏情况）会撞 60s 挑战超时。**

### 2.2 Java / MySQL / 登录与读路径（5000 学生规模下）

| 场景 | 压力 | P50 | P95 | 吞吐 | 结论 |
|---|---|---|---|---|---|
| 登录（5000 账号库） | 200 次 / 50 并发 | 603ms | 962ms | **73.4 TPS** | 延迟大头是 BCrypt 校验（安全成本，非查询瓶颈） |
| 学生读路径：签到历史 | 200 次 / 50 并发 | 44ms | 98ms | — | 健康 |
| 学生读路径：进行中场次 | 200 次 / 50 并发 | 46ms | 93ms | — | 健康 |
| **5000 人大班开场**（插 5000 行 session_expected + expectedCount） | 单次 API | **46.4ms** | — | — | 健康得超出预期 |
| 教师考勤分页（大班 session） | 3 次采样 | 12~14ms | 14ms | — | 索引有效（idx_student_class 等） |
| **导出 xlsx（5000 行，8 列）** | 单次 API | **495ms**，169KB | — | — | POI 生成开销可接受 |

## 3. 关键发现

### 3.1 1:N 暴力点积检索完全撑得住 5000+ 学生

向量库从 1086 → 6171（5.7 倍），**串行签到端到端耗时无变化**（154.3s → 148.2s，-4%，属运行抖动）。`VectorIndex.search_top2` 的 NumPy 点积在 6166×512 维规模下仅 ~1-3ms，相对每帧数百毫秒的 CNN 推理可忽略。CR-002 中「万级向量再考虑 ANN、先用数据说话」的决策得到数据支撑：**当前架构在 1 万人规模内不需要引入 FAISS**；若未来到 5 万+向量，再评估。

### 3.2 真正的瓶颈是 face 推理 worker 数，不是检索

- 8 并发时每帧 2.35s（串行 ~0.3s 的 8 倍），14 并发 3.63s，30 并发 8.55s——全部是 **2 个 uvicorn worker 前的排队**；
- 30 并发时单条链路（start + 8 帧 + finish）耗时 > 60s，撞挑战 TTL → FA-3002 大面积失败；
- **扩容路径明确**：`FACE_WORKERS` 2→8（本机 8 核可支撑），预计线性提升吞吐；或前端维持真实节奏（学生端每帧 ~1s 间隔，30 人课堂实际到达率 ≈ 30 帧/s，远低于压测机的峰值连发）。

### 3.3 合成素材的活体分存在个体差异（压测方法学发现）

串行对照证明 match 门禁全部 0.95+ 通过，但合成 MOVE_CLOSER 素材的活体分因源图而异（0.02~0.34 的低分样本 vs 0.55+ 的合格样本）。压测池必须**先用串行逐人筛选**（`bench_pool.py`），否则素材噪声会污染成功率数据。此结论对真实用户的含义：活体门禁对真人（有微动、传感器噪声）不会像对合成图这样严苛，BPCER 验收仍以真机素材为准。

### 3.4 一个部署陷阱：同一向量复制给多个学生会破坏 margin 门禁

首轮造数把 14 名压测学生的向量也复制给了负载学生（top2 相似度 ≈ top1 → margin < 0.06 → FA-4005 match 拒）。已用 `seed_bench5000_fix.sql` 修复（删除同 blob 副本、用非压测身份向量补齐）。**生产含义：跨学生共享向量数据（如批量导入错绑）会以「margin 不足」的形式出现，归因时先查数据。**

## 4. 建议与后续

1. **短期**：`FACE_WORKERS=2 → 8`（compose env 一行改动 + 重建容器），重跑本压测验证 c30 恢复；预期每帧排队从 8.5s 降至 ~1-2s。
2. **中期**：Java 侧无需动（46ms 开场、12ms 分页、495ms 导出余量充足）；登录如需更高 TPS 可评估 BCrypt strength 降级（不建议，安全成本合理）。
3. **压测资产沉淀**：`tests/e2e/bench5000.py` / `bench_pool.py` / `bench_solo.py` 可复用；`deploy/seed_bench5000.sql` 幂等可重放。
4. **回滚清单**（压测数据清理，待确认后执行）：删除 `B5K%` 学生/账号/档案/向量、`STRESS5K%` 班/课/教师/授权、POOL/BENCH/SOLO 压测 session（class_id=29 的 207~233 场）及 attendance/session_expected、`bench_casia_pwd_backup` 表（C0~C99 原始密码 hash 已备份，未回写——如需恢复执行 UPDATE 回写）。

## 5. 复现命令

```bash
# 基线（造数前）
cd deploy && docker compose --env-file .env --profile test run --rm -T e2e \
  python /e2e/bench5000.py --mode baseline
# 造数（幂等）
docker exec -i fa-mysql-1 sh -c 'MYSQL_PWD=*** mysql -uroot face_attendance' < deploy/seed_bench5000.sql
# 等快照同步（10s 轮询）：docker logs fa-face-1 | grep 'snapshot loaded'
# 全量
docker compose --env-file .env --profile test run --rm -T e2e python /e2e/bench5000.py --mode full
# 串行筛选签到池
docker compose --env-file .env --profile test run --rm -T e2e python /e2e/bench_pool.py
```
