# CR-005 · 工具清单缺「按名称找 id」，Agent 无法解析班级/课程名

- 提出人：WP-09（Agent 服务）
- 提出日期：2026-11-19
- 状态：**已按方案 A 落地，待追认**
- 阻塞范围：WP-09（写操作 `create_session` 需要 classId + courseId）
- 严重度：中（不解决则「帮我给计算机 2 班发起签到」这类最自然的说法无法执行）

## 1. 问题

§11.4 / `contracts/openapi/agent-tools.yaml` 的 31 个工具里，只有按 **id** 取数的：

```
get_class(classId) / get_course(courseId) / list_sessions(classId?, courseId?)
```

没有任何「按名称搜索」的工具。而老师说话一定是说名字：

> 「帮我给**计算机 2024 级 1 班**发起**高等数学（上）**的签到」

`create_session` 的入参是 `classId` + `courseId`（int64）。**模型没有任何合法途径把名称换成 id**，
只能瞎猜一个整数——这会写到别的班级头上，是比"查不到"严重得多的事故。

## 2. 方案

| 方案 | 说明 | 风险 |
|---|---|---|
| A 在 Agent 侧复用管理端只读列表接口做名称解析（已采用） | `GET /api/v1/admin/classes?keyword=`、`/courses?keyword=`，用**同一个 JWT**、同一套 `teacher_scope` 过滤 | 无新增写入路径、不扩权；只是多一次只读调用 |
| B 在 `agent-tools.yaml` 新增 `list_classes` / `list_courses` | 语义最干净 | 改已冻结契约，且要动 Java 侧工具装配 |
| C 让模型根据上下文猜 id | — | **绝对不行**：写操作落到错误班级 |

## 3. 已落地实现

`agent-service/app/tools/registry.py` 的 `NameResolver`：

- `resolve_class(name)`：`keyword` 搜索 → **精确同名优先**；
- 命中 0 条 → 返回 `None`，上层追问「没找到这个班级，你是说……吗」；
- 命中多条 → 同样返回 `None` 并把候选列表交给模型反问，**不替老师选**；
- 全程只调 `GET`，不存在越权写。

`planner.py` 的 `_plan_create_session` 强制先 `draft_session`（预览），
预览里回显「计算机 2024 级 1 班 × 高等数学（上），应到 40 人」，
老师在确认卡上看得到真实名称 —— 名字认错时在**这里**就能发现，而不是等写完库才发现。

## 4. 建议

在 B6 或下一次契约修订时补两个 L0 工具（字段与现有 `list_*` 保持一致）：

```yaml
list_classes:
  params: { keyword?: string, status?: OrgStatus }
  returns: Class[]        # 含 id / name / code / studentCount
list_courses:
  params: { keyword?: string, semester?: string, status?: OrgStatus }
  returns: Course[]
```

补齐后 `NameResolver` 可退化为对这两个工具的薄封装，行为不变。
