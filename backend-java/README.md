# backend-java — 业务主服务

系统里**唯一可以写业务数据**的服务。账号、班级、课程、学生、签到会话、考勤、申诉，全部由它落库。

## 职责

| 模块 | 内容 |
|---|---|
| `fa-auth` | 登录 / 刷新 / 学生激活 / JWT / Redis 黑名单 / 单管理员约束 / 教师 CRUD |
| `fa-org` | **班级 CRUD**、**课程 CRUD**（两者独立）、学生、`teacher_scope`、CSV 导入 |
| `fa-checkin` | 会话生命周期（班级 × 课程）、二维码、commit 幂等、WebSocket 推送 |
| `fa-appeal` | 申诉流转、考勤调整 |
| `fa-agent` | Agent 网关：工具目录、L0~L3 工具、两阶段确认（`confirmToken`）、执行留痕 |
| `fa-audit` | 审计切面与查询 |

## 边界（不做什么）

- 不做人脸推理 —— 那是 `face-service-python` 的事，本服务只接收判定结果。
- 不做界面。
- 不直接对外暴露内部接口：`/internal/**` 仅容器网络可达，校验 `X-Internal-Token`。

## 技术栈

Java 17 + Spring Boot 3.2 + Spring Security + MyBatis-Plus + Flyway + Redis + WebSocket。
Maven 多模块，依赖方向：`bootstrap → 业务模块 → infra → common`，**禁止反向**。

## 对应文档

- 模块划分与分层：§9.1、§9.2
- 关键实现约束（单管理员、JWT、数据范围、幂等、审计）：§9.3
- 接口契约：§6
- 表结构：§5.3

## 当前状态

**B0：仅目录占位。** 代码由 B2（`fa-auth` / `fa-org`）与 B4（`fa-checkin` / `fa-appeal` / `fa-audit`）实现。

## 本地启动（待 B2 就位后可用）

```bash
cd backend-java
mvn -B -ntp spring-boot:run -pl fa-bootstrap -am
```
