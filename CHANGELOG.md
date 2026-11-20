# Changelog

本文件记录可交付版本的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

批次完成标记（`batch-N-done`）与版本 tag 是两套，不写入本文件；本文件只记**可交付版本**。

## [Unreleased]

### Security
- **人脸向量字段级加密**（CR-012）：`face_vector.embedding` 以 AES-256-GCM 落库
  （密文 = 版本 1B + IV 12B + 密文 + tag 16B）。密钥 `FA_VECTOR_AES_KEY` 经
  `deploy/.env` 注入 java 容器（32 字节 base64，未配置 = 明文落库并 WARN，仅限
  本地/CI）；存量明文行兼容读取，无需停机迁移。`InternalVectorController` 成为
  解密边界，Python 快照协议不变。新增 `VectorCrypto` 组件与 9 个纯逻辑单测。
- **备份加密**（CR-012）：`backup.sh` 在 `BACKUP_AES_KEY` 配置后对 dump 整体加密
  （AES-256-CBC + PBKDF2 600k 轮，明文不落盘）；`restore.sh` 支持 `.enc` 备份，
  口令错在 openssl 阶段直接退出不污染目标库。

### Changed
- §13.3 口径更新：人脸原图不落盘（face 服务内存处理后即丢弃），MinIO 仅在启用
  "人工复核看原图"需求时按对象加密 + 90 天清理落地（原稿与实现不符，CR-012 记录）。

## [1.0.2] - 2026-11-19

### Changed
- 考勤导出由 CSV 改为 Excel（.xlsx）：`GET /api/v1/admin/attendance/export` 响应类型更换，
  前端按钮改为「导出 Excel」，文件名 `attendance-<日期>.xlsx`。缺勤（ABSENT）学生整行
  红底高亮（浅红底 ROSE + 深红字 DARK_RED），其余行无底色；表头冻结、列宽固定。
  CSV 是纯文本存不了颜色，此为用户裁决的格式替换，非并留双端点。
- `fa-checkin` 新增依赖 `poi-ooxml 5.5.1`（父 pom 统一管版本）。

### Added
- `fa-checkin` 单测 `AttendanceExportXlsxTest`：不碰数据库，直接断言 ABSENT 行
  SOLID_FOREGROUND + ROSE 底色 + 深红字，PRESENT/LATE 行无底色。

## [1.0.1] - 2026-11-19

### Fixed

- **移动端人脸采集提交报「权限不足」且无后续**（v1.0.0 部署版实测）：教师/管理员账号在 `/m/login`
  登录后能一路走到采集页并采满 5 张样本，直到最后一步提交才被 Java 端
  `@PreAuthorize("hasRole('STUDENT')")` 以 403 FA-1003 拒绝，用户感知为"权限不足 + 没反应"。
  根因是移动端登录页 / 路由守卫 / 首页入口均不区分角色。三处修复（纯前端）：
  - 登录成功后发现非 STUDENT → 立即登出并提示「移动端仅限学生使用，请用学号登录」（`LoginView.vue`）；
  - 路由守卫对非公开路由校验角色，覆盖 localStorage 里残留的教师旧令牌（`router/index.ts`）；
  - 采集提交捕获 FA-1003 时给出可行动文案（`EnrollView.vue`，最后防线）。

### 排查记录（踩过的坑）

- **face-service `/face-api/enroll/sample` 不验签**：只检查 Authorization 非空——教师/过期令牌也能
  正常采满样本，把 403 推迟到最后一步才暴露。信任边界在 Java 侧；若要收紧需走 CR。
- **`activate()` 不更新 `last_login_at`**：学生激活后该列保持 NULL，排查时不能拿它判断
  "该账号是否登录过"。
- **沙箱内 buildx 构建被拦**：`docker compose build` 报 `failed to update builder last activity time:
  rename .../.docker/buildx/activity/... operation not permitted`；`DOCKER_BUILDKIT=0` 走经典
  builder 可通过。
- **前端改动的快速验证链路**：`npm run build` → `docker cp dist/. fa-nginx-1:/usr/share/nginx/html/m/`
  即可热替换生效；`docker compose restart` 不会还原它（只有 `up -d --build` / `--force-recreate`
  才会从镜像重建），适合先验证再重建镜像。

## [1.0.0-rc] - 2026-11-19（B0~B5 全部内容）

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

## [1.0.0] - 2026-11-19

首个可交付版本：B0~B6 全部批次。B0~B5 内容见下方各段，B6（部署与验收）如下。

### Added（B6）

- **`deploy/` 六服务编排**：mysql / redis / java / face / agent / nginx，`e2e` 验收容器
  （`profiles: [test]`）。三份多阶段 Dockerfile（maven→JRE、python-slim、node→nginx 静态），
  构建上下文 3.2GB→40MB。绑定目录（`deploy/data`）便于宿主机备份。
- **nginx 单点入口**：TLS 1.2+、HTTP→HTTPS 301、HSTS/nosniff/referrer、gzip、
  `/face-api/` 8MB 上限 + 10r/s 限流、WebSocket 升级透传、`/agent/` 去 X-Frame-Options。
- **运维脚本八件套**：`gen-tls` / `healthcheck` / `wait-healthy` / `verify-coldstart`（冷启动计时 Gate）/ 
  `verify-nginx`（14 项逐条断言）/ `backup`+`restore`（sha256 校验 + 独立库恢复比对）/
  `run-e2e`（两阶段执行器）。
- **`tests/e2e/` 18 条系统级验收**（对应文档 16.3）+ 30 并发压测 + 60 行最小 WS 客户端 +
  身份轮换（DUPLICATE 规避 + 动作可测性预检）。
- **`POST /mobile/face/enroll`**：移动端人脸建档（PENDING_REVIEW + face_vector）。
- **`FA_CHALLENGE_TTL_SEC`**：挑战 TTL 可配（默认 60 = 契约值），供按推理容量调整。

### Changed（B6）

- **大屏 WS 鉴权后置**：`GET /admin/sessions/*/live` 从 `authenticated()` 改为放行，
  鉴权由握手后 `LiveWsConfig.authorized()`（验签+角色+黑名单）完成——浏览器 WS 无法带
  Authorization 头，JWT 只能走 query（契约如此）。
- **动作提示白名单（CR-010）**：`_pick_action(hint)` 只在 `FA_ALLOW_ACTION_HINT=1` 且
  hint 在白名单内时生效，生产默认关闭；契约外动作码直接拒绝。
- `gzip_min_length` 1024→256（SPA index.html 只有几百字节）。

### Fixed（B6）

18 场景 E2E 与 30 并发压测暴露的真实缺陷：

- **大屏 WS 从未连通**：`SecurityConfig` 拦截 `/live` 路径（见 Changed）。
- **`join` 不校验二维码**：`qrToken` 无 `@Valid` 也无 `qr.verify`，伪造/过期码能进挑战。补 FA-3002。
- **学生端班级/课程名恒 null**：`jdbc.queryForObject` 误用 RowMapper lambda，改 `jdbc.query`。
- **申诉创建无实时推送**：`AppealService` afterCommit 推 `EVT_APPEAL_CREATED`，大屏弹提示。
- **审计缺前后状态**：`AuditWriter.write(before, after)` 摘要，申诉审批留 PENDING→APPROVED。
- **基线污染误拒**：基线帧已张嘴时动作计数器基线虚高 → 正常签到被判 FA-4003。
  `MouthActionCounter` 增加一次性基线自愈。
- **引擎懒加载无锁**：多线程并发首次 `load_all()` 互相覆盖拿 None；加载半失败后
  `_registry` 永久半赋值。双检锁 + 加载完才赋全局。
- 验收工具十余处：macOS bash 3.2 的 `declare -A` / `$var（全角` 兼容、curl h2 测不了 WS
  握手（改标准库 socket）、`gzip_min_length`、压测脚本引用旧 API 等（详见 `B6-验收报告.md` §3）。

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
