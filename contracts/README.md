# contracts — 契约区（冻结后只读）

存放**跨包共享**的契约：数据库 DDL、OpenAPI、枚举、错误码、断言脚本。

## 铁律

- B1 完成后打 `contract-v1` tag，之后**任何包只能按契约实现，不得擅自改字段**。
- 发现契约缺陷：写 `docs/change-request/CR-xxx.md`，由人工裁决后统一修改，不要在业务包里"先改再说"。
- 本目录的改动必须单独提交，commit 类型用 `docs(wp-01)` 或 `feat(wp-01)`，不与业务代码混在一起。

## 实际结构（B1 已交付）

```
contracts/
├─ openapi/
│  ├─ java-api.yaml      # 三组 tag：auth / mobile / admin，36 个操作
│  ├─ face-api.yaml      # health + challenge 三接口 + enroll/sample
│  ├─ internal.yaml      # 供 Python 与 Agent 调用的内部接口 + 两阶段确认
│  └─ agent-tools.yaml   # 31 个工具定义（L0=10 / L1=5 / L2=13 / L3=3）
├─ enums/
│  ├─ roles.json         # ADMIN / TEACHER / STUDENT
│  ├─ statuses.json      # 9 组状态机 + rejectGate 小写归因标签
│  ├─ actions.json       # 4 个启用动作（靠近/离远/左转/右转）+ 停用动作及原因（含张嘴族、闭眼、眨眼）
│  └─ error-codes.json   # 35 个错误码，含 HTTP 状态与领域
└─ scripts/
   ├─ check-contract.sh       # 扫 information_schema，拦 course.class_id 等
   └─ check-agent-tools.py    # 拦"返回含手机号""复合工具""L2 未要求确认"
```

数据库迁移脚本在 `db/`（不在 contracts 下，因为它由 Flyway 管理、有版本顺序）。
两者的关系：`db/migration/V1__schema.sql` 是 DDL 的唯一实现，`contracts/` 是接口与枚举的唯一实现。

## 怎么用

```bash
# 1. 校验四份契约文件
npx @openapitools/openapi-generator-cli@2 validate -i contracts/openapi/java-api.yaml
npx @openapitools/openapi-generator-cli@2 validate -i contracts/openapi/face-api.yaml
npx @openapitools/openapi-generator-cli@2 validate -i contracts/openapi/internal.yaml
python3 contracts/scripts/check-agent-tools.py

# 2. 校验数据库结构（需要一个已建好表的库）
MYSQL_HOST=127.0.0.1 MYSQL_USER=root MYSQL_PASSWORD=*** MYSQL_DATABASE=face_gate \
  ./contracts/scripts/check-contract.sh
```

CI 的 `契约校验` job 会自动跑这两组，任何一次改动都要过。

## 最容易出错的地方

| 项 | 约定 | 谁在守 |
|---|---|---|
| **班级与课程解耦** | `course` 无 `class_id`；`class_group` 无 `course_id`；不建 `course_student` | `check-contract.sh` |
| **课程 DTO 不得带 classId** | 请求体 `additionalProperties: false`，服务端未知字段校验 | `java-api.yaml` 的 `CourseCreateReq` |
| 动作码 | 只用 `actions.json` 里 `enabled` 的 4 个：`MOVE_CLOSER` / `MOVE_AWAY` / `TURN_LEFT` / `TURN_RIGHT`（CR-018 停用张嘴族、CR-029 停用闭眼 `EYE_CLOSE`；存量 `action_seq` 仍可读） | `actions.json` + `check-agent-tools.py` |
| 基线帧字段 | 统一 `baselineFrame`（Base64），响应含 `baselineDegraded` | `face-api.yaml` |
| 会话 | 必须同时含 `class_id` 与 `course_id`，缺一 `FA-2001` | `V1__schema.sql` + `java-api.yaml` |
| Agent 写接口 | 请求体 `{params, confirmToken?}`，响应含 `requiresConfirm`/`preview`/`confirmToken`/`expiresAt` | `internal.yaml` |
| Agent 工具返回 | 不得含 imageUrl / embedding / phone / idCard | `check-agent-tools.py` |
| `rejectGate` | **小写**（`liveness` 等），与其他枚举的大写约定相反，是有意的 | `statuses.json` |

## 版本与冻结

| 项 | 值 |
|---|---|
| 版本 | `1.0.0` |
| 冻结 tag | `contract-v1` |
| 冻结日期 | 2026-11-19 |
| 校验命令 | 见上「怎么用」，CI 每次提交都跑 |

> 冻结的意思不是"永远不能改"，而是"改必须留痕"：先提 CR，裁决后由 WP-01 统一改，
> 再通知受影响的工作包同步。B1 期间已经提了 `CR-001`（应到名单快照），可作为流程范本。
