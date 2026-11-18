# agent-service — 智能助手（LangGraph）

把自然语言变成"查得到的数据"和"确认后执行的操作"。**编排用 LangGraph，Streamlit 只做界面壳。**

## 能力分级

| 级别 | 能力 | 确认 | 默认 |
|:--:|---|:--:|:--:|
| L0 | 只读查询（考勤、名单、统计、趋势） | 否 | 开 |
| L1 | 生成草稿（导入预览、申诉回复、缺勤名单、小结） | 否（不落库） | 开 |
| L2 | 写操作（发起/关闭签到、补签、请假、处理申诉、建班级/课程） | **是** | 开 |
| L3 | 定时与批量（周期报表、批量提醒） | 是 + 管理员授权 | 关 |

## 边界（不做什么）

- 不直接连数据库，所有数据经 Java 的 `/internal/agent/tools/*`。
- 不读写人脸原图或向量 —— 工具 DTO 里根本没有这些字段（结构性保证）。
- 不自行构造 `confirmToken`；写操作必须走"预览 → 用户确认 → 带令牌重放"。
- 一次确认只对应一个工具调用，不做复合写操作。

## 图结构

```
START → intake → route ─┬→ answer                    （闲聊）
                        ├→ plan → tools → verify → answer   （查询/草稿）
                        └→ plan → tools(dry-run) → confirm(interrupt) → commit → answer
```

检查点存 Redis（`/2` 库），`thread_id = userId:conversationId`，保留 24 小时。

## 对应文档

§11（整个 Agent 章节：图设计、状态、工具清单、五道闸）、§2.2 D8（分级决策）。

## 当前状态

**B0：仅目录占位。** 由 B5（WP-09）实现。

## 本地启动（待实现后）

```bash
cd agent-service
streamlit run app/main.py --server.port 8501
```

未配置 `LLM_API_KEY` 时必须**优雅降级**（提示而非崩溃），快捷问句仍可用。
