# CR-002 · 向量下发端点缺失（Python 无法按契约取到人脸向量）

- 提出人：WP-05（B3 实现）
- 提出日期：2026-11-19
- 状态：✅ **已批准并实现**（2026-11-19 业主批准）
- 阻塞范围：B3 的 enroll 落库、B4 的签到比对真实数据
- 严重度：高（B3 当前的向量库只能用 JSON 文件喂测试数据）

## 1. 问题

§5.3 的单一写入口原则：`face_vector` 表由 Java 写，Python 只读。
但 B1 冻结的 `contracts/openapi/internal.yaml` 里只有四个内部端点：

```
GET  /internal/sessions/{id}/context
POST /internal/checkin/commit
GET  /internal/agent/tools/catalog
POST /internal/agent/tools/{tool}
```

**没有任何一个能把「已审核通过的学生人脸向量」下发给 Python。**

结果：Python 的 1:N 索引没有合法数据来源。直连 MySQL 违反"不碰业务库"
（§14.3 WP-05 明确写了），不连就拿不到数据——两头堵。

## 2. 建议：新增两个内部端点

```yaml
# 增量拉取（Python 每分钟轮询一次，按 updated_at 增量）
GET /internal/face/vectors?since={iso8601}
→ { "vectors": [ {"studentId": 1, "vector": [512 floats], "updatedAt": "..."} ], "serverTime": "..." }

# 单条删除（学生被删除/归档、人脸被驳回时）
POST /internal/face/vectors/remove
→ { "studentIds": [1,2] }
```

鉴权沿用 `X-Internal-Token`。增量而不是全量：向量全量约 500 条 × 512 维 × 4B ≈ 1MB，
全量轮询浪费；但**首次拉取必须支持全量**（`since` 缺省返回全部）。

## 3. 影响

| 工作包 | 影响 |
|---|---|
| WP-05（B3） | `app/index/vectors.py` 的数据源从 JSON 文件换成这两个端点，接口签名不变 |
| WP-04（B4） | Java 侧新增 Controller + 查询，属 fa-org/face_vector 的只读面 |
| WP-13（B6） | 验收场景"新录入的学生立即可签"依赖它 |

## 4. 裁决记录

| 日期 | 裁决人 | 结论 | 落地动作 |
|---|---|---|---|
| 2026-11-19 | 业主 | ✅ 批准 | 见下「实现说明」 |

## 实现说明（与原建议的偏离）

批准后实现时做了一处**简化**：只加了 `GET /internal/face/vectors`（全量快照），
原建议的「增量游标 `since` + `POST /internal/face/vectors/remove`」没有实现。

理由：
1. **删除传播**：全量替换天然覆盖删除（学生删了，下次拉取就不在快照里），
   不需要单独的删除通知端点，也不需要「谁调用 remove」的答案；
2. **自愈性**：Python 侧索引状态漂移（崩溃恢复、部分写坏）会被下一次全量替换自动修正，
   增量方案做不到；
3. **流量**：512 维向量按 base64 传，1000 名学生约 1MB / 60 秒，完全可接受；
   增量省下的流量换不来它多出的复杂度（游标管理、漏删风险）。

落地清单：
- 契约：`internal.yaml` 新增 `VectorSnapshot` / `FaceVectorItem`（Redocly 校验通过）
- Java：`InternalVectorController`（fa-org，只读）+ `InternalTokenFilter`（fa-common，
  常量时间比较）+ SecurityConfig 放行 `/internal/**`；学生删除时同步清理 face_vector
- Python：`app/index/sync.py`（60s 轮询全量替换，失败保旧索引）+ 4 个测试
- 测试：`VectorSyncIT`（CI 真库）覆盖 401 / 快照内容 / 删除传播
