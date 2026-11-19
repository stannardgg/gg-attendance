# CR-002 · 向量下发端点缺失（Python 无法按契约取到人脸向量）

- 提出人：WP-05（B3 实现）
- 提出日期：2026-11-19
- 状态：**待裁决**
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
| — | — | 待裁决 | — |
