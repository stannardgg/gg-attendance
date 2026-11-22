# CR-023 · 识别失败三次后锁定本场签到（首页不可再进）

- 提出人：主人（2026-11-22，附学生端首页截图：「识别三次失败后应无法从主页再次点击进入签到功能」）
- 提出日期：2026-11-22
- 状态：✅ **已批准并实现**
- 关联：§4.4.3 挑战规则（WP-07 验收「第 4 次重试 → FA-3005」）/ §6.4 内部接口 / §16.3 验收场景
- 影响工作包：WP-05（Java 签到）、WP-06（Python 编排）、WP-11（移动端）、WP-13（E2E）

## 1. 动机与缺陷还原

WP-07 的验收条款「每会话每人 3 次，第 4 次重试 → FA-3005」此前**没有权威实现**：

1. `join` 响应的 `retryLeft` 是硬编码常量 `3`，从未扣减；
2. Python 配置里的 `MAX_RETRY_PER_SESSION=3` 没有任何代码消费；
3. 唯一生效的是采集页 `ResultPanel` 的本地 `attempts` 计数——**刷新页面即清零**。

实测路径（业主截图场景）：学生刷脸失败 3 次 → 关掉采集页回首页 → 场次仍显示
「待签到」→ 点进去次数从 0 重新开始，可无限重试。三道门禁拦得住冒名顶替，
拦不住「无限次试到过为止」的运气攻击——每次挑战都过门禁，但样本空间无限大。

## 2. 方案

### 2.1 计数落 MySQL（checkin_attempt，V7）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 存储位置 | MySQL 新表 `checkin_attempt`，不放 Redis | 这是**门禁**不是缓存。与「改密失效用 credential_version 不用 Redis 黑名单」同一立场：Redis 故障按「未计数」放行不可接受 |
| 唯一键 | `(session_id, student_id)` | 既是查询路径也是 upsert 锚点；无物理外键（V1 头部全局约定） |
| 写入方 | Java（`POST /internal/checkin/attempt/fail`） | 单一写入口原则（§1.2 D8）不破坏：Python 只上报判定结果，落库仍只有 Java |
| 上限 | `app.checkin.max-retry`，默认 3 | 与 §4.4.3、FA-3005 note 一致；sys_config 的 CHECKIN_MAX_RETRY 种子值 5 是笔误，V7 修正为 3（不能改 V3，Flyway checksum 会拒） |

计数范围：**finish 判失败**（`rejectGate ∈ {liveness, identity, action_count,
match}`）与 **frame 中止**（multi_face / no_face——采集页把它们当 ERROR 计数，
服务端不记的话「三次」只约束门禁判定）。`rejectGate=commit`（Java 不可用）
**不计**：学生没有做错任何事，把基础设施故障算到他头上是转嫁。

计数只增不减：申诉撤回/驳回不清零。申诉是老师的核对通道，不是重试次数的重置键。

### 2.2 三处拦截

| 位置 | 行为 |
|---|---|
| Java `join` | `checkin_attempt.fail_count ≥ 3` → `429 FA-3005`；响应 `retryLeft` 改为真实扣减值 |
| Python `start` | 开挑战前查 `GET /internal/checkin/attempt`，`blocked=true` → `429 FA-3005`（`RetryExceeded`）。**必须查**：join 拦的是领票，失败后票据还有最长 60 秒寿命，旧票据能直接开第 4 场挑战 |
| 首页/入口页 | `sessions/active` 新增 `retryLeft`；`retryLeft=0` 的场次显示「识别失败」（红色标签），点击**不进采集页**，改为带去申诉页（该场次唯一有意义的动作） |

采集页结果面板同样改为以服务端 `retryLeft` / `FA-3005` 决定重试按钮显隐，
本地 `attempts` 降级为展示兜底。

### 2.3 降级方向（两害相权）

Python 查询/上报 Java 失败时**降级放行并告警**：计数缺失的最坏结果是多几次
重试（每次仍要过三道门禁），而误拦的最坏结果是「Java 抖一下 → 全班签不了」。
commit 反正要过 Java，Java 彻底不可用时签到本身就不可达，不存在真实的绕过窗口。

## 3. 改动清单

- `db/migration/V7__checkin_attempt.sql`：新表 + sys_config 修正；
- Java：`CheckinAttemptService`（新建）、`CheckinDtos.AttemptData/AttemptFailReq`、
  `MobileCheckinController`（join 第 ⑥ 道前置检查 + active 回填 retryLeft）、
  `InternalCheckinController`（attempt 查询/上报两个端点）；
- Python：`java_client`（GET 支持 + 两个函数）、`challenge/service.py`
  （`RetryExceeded`、start 预检、finish 上报、中止计数）、`main.py`（FA-3005 映射 429）；
- 契约：internal.yaml 两个端点 + schema；java-api.yaml join 描述与 `ActiveSession.retryLeft`；
  face-api.yaml `FinishData.retryLeft` 补语义（字段本就声明，此前从未有真值）；
- 移动端：HomeView / CheckinEntry 拦截与标签、useFrameUploader `retryBlocked`、
  ResultPanel 权威显隐、errors.ts 文案；
- 测试：`CheckinFlowIT.retryExceededBlocksJoin`（IT）、E2E 场景 22。

## 4. 验收

- 连续 3 次失败上报后：`join` → 429 FA-3005；`sessions/active` 该场次 `retryLeft=0`；
  `GET /internal/checkin/attempt` → `blocked=true`（IT 覆盖）。
- E2E 场景 22：三次真实失败挑战后，第 4 次 join 被拒，finish 的 `retryLeft` 逐次递减。
- 业主路径复测：失败 3 次 → 回首页 → 场次显示「识别失败」→ 点击进申诉页，不再进采集页。
