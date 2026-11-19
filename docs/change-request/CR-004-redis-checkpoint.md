# CR-004 · 检查点后端偏离 §11.8（官方 RedisSaver 不可用）

- 提出人：WP-09（Agent 服务）
- 提出日期：2026-11-19
- 状态：**已按方案 B 落地，待追认**
- 阻塞范围：无（已实现并跑通 13 个单测）
- 严重度：低（不影响契约，只影响实现选型）

## 1. 问题

§11.8 指定检查点用 `langgraph-checkpoint-redis` 的 `RedisSaver`，以实现
**线程级断点恢复 + 跨实例共享**（多人同时对话时，任何一台 agent 容器都能接管）。

实测该库不可用于本项目部署：

```
langgraph-checkpoint-redis==0.6.0   需要 langgraph-checkpoint>=4.1.1
langgraph-checkpoint-redis==0.6.11  需要 langgraph-checkpoint>=3,<4
langgraph==0.6.11                   需要 langgraph-checkpoint<4
langgraph==1.2.22                   需要 langgraph-checkpoint>=4.3,<5   ✅ 唯一自洽组合
```

即便版本对齐，`RedisSaver` 还强制要求 **RediSearch 模块**（`FT.CREATE` / `FT.SEARCH`），
而 §12.1 的部署镜像是 `redis:7-alpine`，**不带 search 模块**。换 `redis-stack` 镜像会：

- 镜像从 32MB 涨到 ~180MB；
- 引入 RediSearch 的许可与运维面；
- 与 B1 已定的 compose 文件冲突。

## 2. 方案

| 方案 | 说明 | 代价 |
|---|---|---|
| A 换 `redis-stack` 镜像 | 直接用官方 `RedisSaver` | 镜像 ×5.6，运维面变大，与 §12.1 冲突 |
| **B 自研 KV+ZSET 检查点（已采用）** | 实现 `BaseCheckpointSaver` 的 `get_tuple/list/put/put_writes/delete_thread`，用 KV 存 blob、ZSET 存索引，24h TTL | 少 30 行官方优化，功能等价 |
| C 退回 `MemorySaver` | 零依赖 | 进程重启即丢断点，"预览→确认"跨重启失效，**不可接受** |
| D 换 Postgres 检查点 | 复用 MySQL 之外的库 | 新增一种存储，运维成本更高 |

## 3. 已落地实现

`agent-service/app/checkpoint/redis_saver.py`：

- `put` 写 `ckpt:{thread}:{ns}:{id}` + ZSET `ckpt_idx:{thread}`（score = checkpoint_id）；
- `list` 按 ZSET 逆序翻页，`limit` 语义与官方一致（含 `before` 游标）；
- `put_writes` 单独存 `writes:{thread}:{id}`，供 interrupt 恢复时重放；
- 全部 key 带 **24h TTL**，`delete_thread` 一并清理；
- Redis 探活失败时 `build.get_graph()` 自动降级 `MemorySaver` 并打 WARN，**不会因为 Redis 抖一下就整站不可用**。

## 4. 验证

- `agent-service/tests/test_graph.py` 8 项全绿，覆盖「预览→取消」「预览→确认→执行」「令牌一次性」「FA-6004 不重试」；
- `thread_id = f"{accountId}:{conversationId}"`，与 §11.3 一致；
- 断点重放不会误报「令牌已用过」——幂等键在消费令牌**之前**查（见 WP-09 交接 §3）。

## 5. 建议

追认方案 B，并在 §11.8 把「检查点用 `RedisSaver`」改为：

> 检查点必须落在 **Redis（外置、跨实例共享、24h TTL）**；实现可为官方 `RedisSaver`
> （需 RediSearch）或自研 KV+ZSET 版本。**不得使用进程内存储**，否则断点恢复失效。
