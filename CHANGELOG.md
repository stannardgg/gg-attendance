# Changelog

本文件记录可交付版本的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

批次完成标记（`batch-N-done`）与版本 tag 是两套，不写入本文件；本文件只记**可交付版本**。

## [Unreleased]

### Added
- 工程基线（B0）：CI、密钥扫描、pre-commit、PR/Issue 模板、`docs/` 三个子目录、七个模块骨架。
- 数据库结构（B1）：15 张表全量 DDL、幂等 seed（含 33 条阈值配置）、单管理员生成列约束、
  申诉 PENDING 唯一约束。
- 接口契约（B1）：`java-api`（51 个操作）、`face-api`（5 个）、`internal`（5 个）、
  `agent-tools`（31 个工具定义，L0=9/L1=5/L2=14/L3=3）。
- 枚举与错误码（B1）：4 份 JSON，33 个错误码，9 组状态机，2 个启用动作。
- 契约断言（B1）：`check-contract.sh`（D7 班级课程解耦）、`check-agent-tools.py`（工具分级与字段白名单），
  均已接入 CI 的 `contract` job。
- `docs/change-request/CR-001`：应到名单快照缺口，待裁决。

### Changed
- CI 新增 `contract` job，带 MySQL service，任何契约违规都会红灯。

## [2.0.0-doc] - 2026-11-18

文档层面的大版本，无运行时代码。

### Changed
- 动作库改为「可测性白名单」：砍掉眨眼 / 转头 / 点头，只保留张嘴（`MOUTH_ONCE` / `MOUTH_TWICE`），随机性放在次数上。
- 动作判据从「关键点开合比」改为「固定 ROI + 轮廓点刚性搬运 + 暗像素占比 + 连续段计数」。
- 活体判定从加权平均改为三道硬门禁（活体中位数 / 首末帧身份一致性 / 动作次数），新增 `FA-4007` 语义。
- 多脸拦截增加 90px 尺寸下限（`MULTI_FACE_MIN_PX`）。
- **班级与课程彻底解耦**：移除 `course_student`，`course` 表不含 `class_id`；新增 `class_group` / `course` / `teacher_scope` 定义；签到会话同时持有 `class_id` + `course_id`。
- Agent 从只读助手升级为 LangGraph 编排的四级能力（L0 只读 / L1 草稿 / L2 写操作 / L3 定时批量）。
- 交付波次改为 Batch 0~6，明确每批「做什么 / 不做什么 / Gate / Git 动作」。

### Added
- 挑战接口新增 `baselineFrame`（指令前基线帧），缺失时降级并标记 `baselineDegraded`。
- 后台新增班级管理、课程管理两个独立 CRUD 页面。
- `agent_action_log` 表与两阶段确认（`confirmToken` + 参数哈希 + 30 分钟撤销）。
- 第 19 章 Git 规范：建仓、分支、提交、发版、密钥泄漏应急。

## [1.0.0-doc] - 2026-11-18

### Added
- 初版项目文档：总纲 + 契约 + 13 个工作包 + 6 个交付波次。
