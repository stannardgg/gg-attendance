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
- Java job 升级为带 MySQL+Redis service，跑 `mvn -Pintegration verify`（单测+集成测试）。

### Added（CR-002）
- 契约新增 `GET /internal/face/vectors`（向量全量快照，CR-002 批准）
- Java：InternalVectorController + InternalTokenFilter；学生删除同步清理向量
- Python：60 秒轮询全量替换索引，拉取失败保旧索引

### Added（B3）
- 人脸服务 `face-service-python`：四模型引擎（SCRFD/ArcFace/2d106det/MiniFASNet）、
  动作计数（固定 ROI + 连续段）、挑战状态机（Redis）、三道门禁、FastAPI 五端点。
- `scripts/calibrate.py`：CASIA 100 人标定，契约默认 0.45 实测 FAR/FRR 均 0.000%。
- `scripts/probe_action.py`：动作探针（真人视频模式 + 合成自检 3/3）。
- `scripts/probe_attack.py`：照片攻击拒绝率 96.0%（原始照片 100%）。
- `docs/change-request/CR-002`：向量下发端点缺失，待裁决。

### Added（B4）
- Java 签到内核：`fa-checkin`（会话创建/关闭、二维码 HMAC 轮换、签到 commit、考勤调整、申诉处理）。
- 组织模块 `fa-org`：班级 / 课程 / 学生 / 教师 / 授权范围 CRUD，CSV 批量导入（逐行校验、已成功行不回滚）。
- 21 项 HTTP 冒烟转 CI，审计切面统一写 `audit_log`。

### Added（B5）
- **Agent 后端 `fa-agent`（新模块）**：`/internal/agent/**` 三端点（catalog / tools / undo）、
  一次性确认令牌（60s、`GETDEL` 单次消费、`paramsHash` 绑定参数、落库只存 SHA-256）、
  `agent_action_log` 留痕与 30 分钟撤销窗口、`AGENT_LEVEL_*` 四级开关（15s 缓存）。
- **后台系统模块 `fa-system`（新模块）**：`/api/v1/admin/stats/overview`（今日四卡 + 近 7 天趋势，
  口径 `(PRESENT+LATE)/应到`，分母取 `session_expected`）、`/api/v1/admin/audit-logs`（操作人三表兜底、
  IP 脱敏到 /24）、`/api/v1/admin/config` GET/PUT（`editable=0` 拒改，改动留审计）。
- **考勤查询 `fa-checkin` 补充**：`/api/v1/admin/attendance` 列表（单场以 `session_expected` 为基表
  LEFT JOIN，未签到的人也在名单里）、`PATCH /{id}`（改状态必须填 2~255 字原因）、`GET /export`（CSV 带 BOM）。
- `db/migration/V3__seed_config.sql`：16 项配置种子（FACE 5 项只读、`AGENT_LEVEL_L0~L3`、
  `CONFIRM_TTL_SEC`、`UNDO_WINDOW_SEC` 等），`INSERT IGNORE` 兼容老 MySQL。
- **Agent 服务 `agent-service`**：LangGraph 7 节点编排（intake/router/planner/tool/verify/confirm/answer）、
  `interrupt()` 人机回环、串行工具调用（8s 超时、5xx 重试 1 次、FA-6004 不重试）、
  自研 Redis 检查点（KV+ZSET，24h TTL，Redis 不可用降级 MemorySaver）、Streamlit 界面壳与确认卡。
- **管理端 `admin-web`**：13 个页面（登录/概览/班级/课程/学生/教师授权/场次列表/发起签到四步/
  二维码大屏/考勤/申诉/智能助手/审计/配置），路由守卫 + 按钮级权限，大屏 WebSocket 实时 +1。
- **移动端 `mobile-web`**：8 个页面（登录/激活/首页/扫码入口/采集/人脸采集/申诉/我的），
  采集状态机（BASELINE→CAPTURING）、固定 4fps 本地节奏 + 串行上传队列（上限 4 帧）、
  §7.4.1 六类真机约束逐条落地（设备枚举先于开流、断流直接重连、黑屏分级重连 ≤2 次、
  探测与校验同参数、成功冷却 6s / 失败递增退避、change 事件屏蔽）。
- `docs/change-request/CR-004~CR-008`：检查点后端 / 名称解析缺口 / sso 兑换端点 /
  会话详情端点 / 移动端探测端点，均待追认。
- `docs/handover/WP-09.md`、`WP-10.md`、`WP-11.md`、`B5-验收报告.md` + 9 张联调截图。

### Fixed（B5）

三端真实联调暴露的问题，都是"单看代码没问题、跑起来才炸"的那类：

- **撤销返回 500**：`create_class/course/session` 的主键是执行时才生成的，`UndoFn` 却只从 `params` 取。
  签名补 `after` 快照，新建类从执行结果取 id。
- **Agent 会话整段存不进 Redis**：LangGraph 的 `checkpoint["ts"]` 是 ISO 字符串，代码直接 `float()`。
  加 `_score()` 解析，不可解析退回当前时间。补 4 个单测。
- **后台三个契约端点 404**：`stats/overview`、`audit-logs`、`config` 只在契约里，后端从未实现。新建 `fa-system`。
- **大屏「应到 0」/ 考勤行无法定位**：签到是"签了才建 `attendance` 记录"，只查 `attendance` 看不见未签到的人。
  单场查询改以 `session_expected` 为基表；`Row.id` 允许 null，前端据此禁用"改状态"。
- **概览趋势只有 1 天**：默认区间取了 `today~today`。改近 7 天，今日卡片不受影响。
- **配置页空白**：`sys_config` 在 V1 只建表没种子，补 V3。
- **审计动作全是英文键**：前端映射写 `class.create`，后端实际写 `CLASS_CREATE`，统一为后端格式并兼容旧写法。
- **移动端首页白屏**：契约 `sessions/active` 是"单条或 null"，前端当数组 `.map()`。两处改 `ActiveSession | null`。
- **归档开关无效**：前端传空串表示"全部"，被 `buildUrl` 过滤成"没传"。显式传 `status=ALL`。
- **改名意图被当成查人**：「把 X 改名为 Y」「本周有多少场签到」分别落到 query 与 `_NAME_STATUS`。
  补 `_RENAME` / `_SESSION_COUNT` 规则与疑问词护栏。
- 前端 `vite dev` 的依赖预构建要批量删缓存，沙箱拦删除导致 dev server 起不来 → 抽出共用 `proxy` 同时挂
  `server` 与 `preview`，用 `vite preview` 验收。

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
