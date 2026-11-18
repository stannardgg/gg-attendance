# 人脸签到系统（gg-attendance）

基于人脸识别 + 指定动作活体的课堂签到系统。学生扫码签到，教师端实时看名单，Agent 可代为查询与操作。

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
| `mobile-web/` | 学生扫码签到端 | Vue 3 + Vite + TS + Vant | B5 |
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

## 许可

内部项目，未授权请勿外传。
