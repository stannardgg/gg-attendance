# contracts — 契约区（只读）

存放**跨包共享**的契约：数据库 DDL、OpenAPI、枚举、错误码。

## 铁律

- B1 完成后打 `contract-v1` tag，之后**任何包只能按契约实现，不得擅自改字段**。
- 发现契约缺陷：写 `docs/change-request/CR-xxx.md`，由人工裁决后统一修改，不要在业务包里"先改再说"。
- 本目录的改动必须单独提交，commit 类型用 `docs(wp-01)` 或 `feat(wp-01)`，不与业务代码混在一起。

## 计划结构

```
contracts/
├─ db/
│  ├─ V1__init.sql              # 全量 DDL（含 class_group / course / teacher_scope）
│  └─ seed.sql                  # 1 ADMIN + 1 教师 + 1 班级 + 3 学生 + 1 课程
├─ openapi/
│  ├─ java-api.yaml             # auth / mobile / admin / internal
│  ├─ face-api.yaml             # 人脸服务
│  └─ agent-tools.yaml          # Agent 工具（function calling schema）
├─ enums/
│  ├─ roles.json
│  ├─ attendance-status.json
│  ├─ actions.json              # 只有 MOUTH_ONCE / MOUTH_TWICE
│  └─ error-codes.json
└─ scripts/
   └─ check-contract.sh         # 契约断言：course 表不得出现 class_id 等
```

## 最容易出错的地方

| 项 | 约定 |
|---|---|
| **班级与课程解耦** | `course` 表不得有 `class_id`；`class_group` 不得有 `course_id`；不建 `course_student`（见项目文档 D7） |
| 动作码 | 只有 `MOUTH_ONCE` / `MOUTH_TWICE`，眨眼/转头已禁用（见 §4.4.1） |
| 基线帧字段 | 统一 `baselineFrame`（Base64），响应里是 `baselineDegraded` |
| 会话 | `checkin_session` 必须同时含 `class_id` 与 `course_id` |
| Agent 写接口 | 请求体统一 `{params, confirmToken?}`，响应含 `requiresConfirm` / `preview` / `confirmToken` / `expiresAt` |

## 当前状态

B0：仅目录与说明。DDL、OpenAPI、枚举由 **B1（WP-01）** 产出。
