# CR-020 · Agent 导出考勤 Excel（并修正导出意图被误判成人名）

- 提出人：主人（2026-11-22 会话内反馈「该 agent 没有导出 excel 表信息的功能，并且近两天识别错误」）
- 提出日期：2026-11-22
- 状态：✅ **已实现，待验收**
- 关联：§11.2 图设计 / §11.4 工具清单 / §11.7 回答与引用 / `agent-tools.yaml`
- 影响工作包：WP-09（Agent 编排与工具契约）、WP-05（考勤导出）

## 1. 现象

管理端「智能助手」输入 **「导出近两天考勤记录」**，得到：

```
未查询到该学生。
数据来源：get_attendance_status / studentName=出近两天、dateFrom=2026-11-22、dateTo=2026-11-22 / 命中 1 条
```

三处都不对：① 没有生成任何文件；② 姓名被解析成「出近两天」；③ 日期范围塌成同一天，
且「未查询到」与「命中 1 条」自相矛盾。

## 2. 根因

| # | 根因 | 位置 |
|---|---|---|
| 1 | `_NAME_STATUS` 用 `[\u4e00-\u9fa5]{2,4}` 抓姓名，从「导出近两天考勤记录」里截出「出近两天」。规则分支在 `_llm_plan` **之前**，模型没有纠正机会 | `planner.py` |
| 2 | `_range_for` 把任何以「近」开头的词都当成本周一至今，「近两天」实际只查了今天 | `planner.py` |
| 3 | 工具契约 L0 只有 9 个查询工具，**没有任何导出能力**；Streamlit 也没有下载入口 | `agent-tools.yaml`、`ui/chat.py` |
| 4 | `_count` 对 `{found:false}` 返回 1，引用行出现「命中 1 条」 | `tool_node.py`、`answer.py` |

第 1、2 条叠在一起才表现成「近两天识别错误」：姓名被猜错 → 查无此人 → 日期又只覆盖当天，
用户看到的就是「明明有记录却说没有」。

## 3. 方案

### 3.1 意图解析（Agent 侧）

- 新增导出意图：`(导出|下载|excel|xlsx|表格|报表)` **且** `(考勤|签到|记录|明细|名单)`，
  且不含「申诉」。位置排在 `_NAME_STATUS` 之前。
- `_NAME_STATUS` 增加非人名词护栏（导出/下载/表格/记录/近两/这两天…），
  即使正则仍截出「出近两天」，也会被丢弃并交给后续分支/模型。
- 新增 `_range_from_text`：`近 N 天 / 最近 N 天 / 过去 N 天`（中文数字「两」也认）、
  `近 N 周`、`前天`、`昨天`、`上周`、`本周`、`本月`，默认今天。
- 班名提取先剥掉动作词与时间词，避免把「导出计算机」当成班名去查。

### 3.2 导出工具（Java 侧）

- `AttendanceQueryService` 新增 `exportXlsxFile(...)`，返回 `ExportFile(bytes, rows)`；
  原 `exportXlsx` 委托它，管理端 `/api/v1/admin/attendance/export` 行为不变。
- `fa-agent` 新增 `ExportTools`，注册 `export_attendance`（**L0 只读**）：

| 项 | 值 |
|---|---|
| 参数 | `sessionId?` / `classId?` / `courseId?` / `dateFrom?` / `dateTo?`（至少给场次或日期） |
| 返回 | `fileName`、`contentType`、`sizeBytes`、`rowCount`、`contentBase64` |
| 口径 | 与 §8 管理端「导出 Excel」**同一段 SQL、同一排序、同一套缺勤红底样式** |
| 上限 | base64 ≤ 4 MiB（约 3 MB 原文件）；超限要求缩小范围，不截断 |
| 留痕 | 走既有 `agent_query_log`（读日志），不产生 `agent_action_log` |

**为什么不做成"Agent 自己用 openpyxl 生成"**：导出必须与管理端逐字一致，
第二套实现在缺勤判定、排序、时间格式上迟早漂移；且它会绕开 `teacher_scope` 的统一过滤。

### 3.3 文件下发（UI 侧）

- `tool_node` 把 `contentBase64` 从 `tool_results` 摘出，放进 `state["attachments"]`：
  文件体不参与引用格式化，也不会被检查点每个 super-step 反复序列化。
- `answer` 对 `export_attendance` 输出固定事实句（行数、文件名、下载位置），
  且**跳过模型润色**——「点消息下方的下载按钮」是操作指引，不是措辞。
- `ui/chat.py` 用 `st.download_button` 渲染；控件 key 取内容哈希，历史里多条导出不会串文件。

### 3.4 计数口径

`found=false` 一律计 0 条（`tool_node._count` 与 `answer._count` 两处同步）。

## 4. 验收

| 项 | 结果 |
|---|---|
| `pytest agent-service/tests` | 31 passed（新增 10 条：导出意图、日期区间 ×3、班名提取、人名护栏、附件抽取、计数 ×2、下载 key 唯一性） |
| `mvn -pl fa-agent test` | 9 passed（含新增 `ExportToolsTest` 文件名断言） |
| `mvn -pl fa-checkin -Dtest=AttendanceExportXlsxTest test` | 1 passed（红底样式未回归） |
| `check-agent-tools.py` | 31 个工具（L0=10），契约断言通过 |

手工验收路径：
1. 管理端打开智能助手，输入「导出近两天考勤记录」→ 应返回「已生成 …共 N 行」+ 下载按钮；
2. 点下载 → 打开 xlsx，缺勤行整行红底；
3. 输入「导出计算机 2 班近两天考勤记录」→ 文件名含日期区间，行数只含该班；
4. 输入「导出考勤记录」（无范围）→ 不应导出全库，提示补日期或场次。

## 5. 风险与回滚

- **响应体变大**：单次导出以 base64 进 state 与 Redis 历史。已设 4 MiB 上限；
  若后续出现频繁大表导出，改为「Agent 侧临时文件 + 一次性下载 token」，不动本 CR 的契约字段。
- **回滚**：删 `ExportTools`、从 `ToolRegistry` 摘掉、`agent-tools.yaml` 移除该工具；
  Agent 侧 `_plan_export` 会在 catalog 里查不到工具并回复「当前服务端还没有启用导出能力」，
  不会误调用。`exportXlsxFile` 是增量方法，保留不影响旧路径。
