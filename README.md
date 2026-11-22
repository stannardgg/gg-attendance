# 人脸签到系统（gg-attendance）

基于人脸识别 + 指定动作活体的课堂签到系统。教师发起签到后，学生手机端直接看到该场次并刷脸完成，教师端实时看名单，Agent 可代为查询与操作。

> **当前状态**：文档已定稿（v2.0），工程基线（B0）已就绪，业务代码尚未开始。
> 全部设计、契约与交付计划见 **[`项目文档.md`](项目文档.md)**（唯一事实来源）。

## 先读哪几节

| 你是 | 读这些 |
|---|---|
| 想快速理解系统 | §3 系统总览、§4 关键业务流程 |
| 要写代码 | §5 数据模型、§6 接口契约，再看 §15 你在哪个 Batch |
| 要改设计 | §2.2 八个关键决策（含替代方案与取舍） |
| 要部署 | §12 部署与联调、§13 非功能与合规 |

## 模块

| 目录 | 职责 | 技术栈 | 负责批次 |
|---|---|---|---|
| `contracts/` | DDL、OpenAPI、枚举、错误码（冻结后只读） | YAML / SQL / JSON | B1 |
| `backend-java/` | 业务主服务：认证、组织、签到、申诉、审计、Agent 网关 | Java 17 + Spring Boot 3.2 | B2、B4 |
| `face-service-python/` | 人脸检测、比对、活体、动作判定 | Python 3.11 + FastAPI + InsightFace | B3 |
| `agent-service/` | LangGraph 编排 + Streamlit 界面，L0~L3 能力 | Python 3.11 + LangGraph | B5 |
| `admin-web/` | 教师 / 管理员后台（含班级、课程管理） | Vue 3 + Vite + TS | B5 |
| `mobile-web/` | 学生签到端（首页直接进入进行中的签到，无需扫码） | Vue 3 + Vite + TS + Vant | B5 |
| `deploy/` | Compose、Nginx、TLS、备份 | Docker Compose | B6 |

## 交付节奏

七个批次 B0~B6，每个批次有明确的职责边界与出口 Gate，见 [`项目文档.md` §15](项目文档.md)。

**每完成一个可验收的功能点，必须提交并推送**，不允许本地堆积。分支、提交信息、发版规范见 §19。

> `main` 已开启分支保护：**不能直接推送**。流程是「开分支 → 提交 → 开 PR → CI 绿 → `gh pr merge --rebase`」。
> 配置项与实测拦截输出见 §19.3。

## 快速开始（B0 阶段尚无业务代码）

```bash
# 1. 克隆
git clone https://github.com/stannardgg/gg-attendance.git
cd gg-attendance

# 2. 安装提交钩子（可选但推荐）
pip install pre-commit && pre-commit install

# 3. 业务代码就位后，按 项目文档.md §12.4 的顺序联调
```

## 约定

- 语言：代码注释与文档用中文，变量名/接口字段用英文。
- 提交：Conventional Commits，scope 用 WP 编号，例 `feat(wp-03): 班级 CRUD`。
- 密钥：只提交 `.env.example`，真实值永不入库。
- 数据：`Cleaned_CASIA_FaceV5/`（评测集）、`models/*.onnx`、`data/` 均已在 `.gitignore` 中排除。

## 启动与停止

全部服务配置了 `restart: unless-stopped`。启动成功标志：healthcheck 输出六服务 healthy + 6 个对外端点 `[OK]`。

| 入口       | 地址                     |
| ---------- | ------------------------ |
| 后台管理端 | https://localhost/admin/ |
| 前台移动端 | https://localhost/m/     |
| Agent      | https://localhost/agent/ |

### 启动项目（环境在 Docker 中，必须先 build）

B6 验收的教训之一：**源码改动不 build 不会进容器**（排查时曾被“配置已改但行为没变”误导）。

```bash
cd deploy   # 在 deploy 目录下运行
docker compose --env-file .env build              # 全部重建，或指定：build java face agent web
docker compose --env-file .env up -d
./scripts/healthcheck.sh
```

### 停止项目

```bash
docker compose --env-file .env stop    # 只停容器，下次 up -d 秒回
docker compose --env-file .env down    # 删容器，但数据卷保留，重建后数据还在
```

⚠️ **切勿使用 `down -v` 或运行 `verify-coldstart.sh` 除非刻意清库**——这两个操作会删除数据卷，数据库内容全部清空重来（`verify-coldstart.sh` 是验收冷启动用的，自带 down -v）。

### 补充说明

- 演示账号：教师张三工号 `teacher`、学生学号 `211`；密码不在仓库公开，见线下交付材料（管理员账号仅线下提供）。
- **调试门 `fa-mysql-gate`**（宿主机 13306）是独立容器、不在 compose 栈里；若它停了，用 `docker start fa-mysql-gate` 单独拉起（仅开发调试用，不影响业务栈）。
- **minio / e2e** 属于 profile 服务（archive/test），默认不启动，无需理会。
- 首次在新机器部署时，`up -d` 会自动完成 Flyway 迁移和初始管理员创建，无需手动初始化。

## 许可

内部项目，未授权请勿外传。
