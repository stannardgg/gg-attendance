# CR-013 · 取消二维码签到入口，学生端直接进入刷脸

- 提出人：主人（2026-11-20 会话内确认）
- 提出日期：2026-11-20
- 状态：✅ **已批准并实现**
- 关联：§2.2 D4 / §3.4 / §4.1 / §4.6 / §6.4 / §7.3 / §8.2 / §9.4 / §12.3 / §13.1 / §16.3
- 影响工作包：WP-04（Java 签到域）、WP-10（管理端前端）、WP-11（移动端）、WP-13（E2E）

## 1. 问题

二维码在整条链路里承担两个职责，而这两个职责现在都不划算了：

1. **把 `sessionId` 送到学生手机**。这在移动端只有 H5 短链、没有登录体系的阶段是必要的。
   现在学生已经用学号登录，`GET /mobile/sessions/active` 能直接告诉他"你班现在有哪场签到"，
   扫码这一步纯粹是多绕一圈。
2. **证明「人在现场」**。这是二维码的真正卖点，但它站不住：二维码可以拍照、截图、转发到群里，
   15 秒轮换只是提高了转发成本，拦不住任何一个愿意配合代签的人。真正拦住代签的是
   活体 + 首末帧身份一致性 + 指定动作这三道硬门禁（D2），而这三项完全不依赖入口。

维持二维码的实际成本则很实在：

- 一套独立密钥（`SESSION_QR_SECRET`）+ 时钟同步假设（15s 窗口、允许前一窗口）；
- 大屏每 15 秒轮询 `/qrcode`、倒计时对齐、`expiresAt` 边界处理；
- `qr_epoch` 换码语义、教师"换码"按钮、Agent `rotate_qr` 工具；
- 学生端 `?tk=` 参数校验、`FA-3002` 错误分支、扫码失败的一整类客服场景。

## 2. 方案

### 2.1 接口变更

| 变更 | 明细 |
|---|---|
| 修改 | `POST /api/v1/mobile/sessions/{sid}/join`：**删除请求体**，不再接收 `qrToken` |
| 修改 | `GET /api/v1/mobile/sessions/active`：由「最多一条，无则 `data=null`」改为**数组**（按 id 倒序，最多 20 条），每项带 `myStatus`；基表由 `student.class_id` 改为 `session_expected` 快照 |
| 删除 | `GET /api/v1/admin/sessions/{id}/qrcode` |
| 删除 | `POST /api/v1/admin/sessions/{id}/rotate-qr` |
| 删除 | Schema `QrData`、`QrResp` |
| 删除 | Agent 工具 `rotate_qr`（`contracts/openapi/agent-tools.yaml` + `WriteTools`） |

### 2.2 join 的准入判定（顺序固定，错误码稳定）

```
1) 会话 status=ACTIVE 且 now < end_at            → 否则 FA-3001
2) session_expected 命中 (session_id, student_id) → 否则 FA-3004
3) 无既有 attendance 记录                          → 否则 FA-3003
4) face_profile.status = ACTIVE                   → 否则 FA-4006
→ 通过则签发 60s checkinTicket + challengeId
```

第 4 条是本次**新增的硬性前置**，也是取消二维码的补偿控制核心。

关于第 2 条：运行期不再实时比 `student.class_id == session.class_id`。应到名单在建会话时已按班级在读学生
快照进 `session_expected`，它既是"你是不是该到"的判据，也是事后算缺勤的基表；
实时比 `class_id` 会让转班学生的历史考勤被篡改（见 CR-001）。

### 2.3 前端

- **移动端**：首页卡片点击直接进 `/checkin?sid={id}`；`/checkin` 无 `sid` 时自己列一遍进行中场次；
  去掉扫码按钮、`tk` 参数与 `FA-3002` 文案。
- **管理端**：大屏左栏由二维码画布改为「签到指引 + 场次信息」（班级 × 课程、应到/已到、剩余时间）；
  去掉换码按钮、`qrcode` 依赖与倒计时对齐逻辑。

### 2.4 配置与数据库

- 移除 `SESSION_QR_SECRET`（compose、`.env.example`、文档 §12.3）。
- `checkin_session.qr_epoch` **列保留不删**：删列要改 Flyway 迁移且对业务零收益，
  代码不再读写即可；列注释更新为"已停用"。

## 3. 影响面

| 位置 | 文件 |
|---|---|
| Java | `MobileCheckinController`、`SessionService`、`AdminSessionController`、`CheckinDtos`、`QrTokenService`（删除）、`WriteTools`（删 `rotate_qr`） |
| 契约 | `contracts/openapi/java-api.yaml`、`contracts/openapi/agent-tools.yaml`、`contracts/enums/error-codes.json` |
| 移动端 | `HomeView.vue`、`CheckinEntry.vue`、`CaptureView.vue`、`LoginView.vue`、`errors.ts`、`WeChatGuard.vue` |
| 管理端 | `SessionLiveView.vue`、`SessionListView.vue`、`shared/api/types.ts` |
| 部署 | `deploy/docker-compose.yml`（去掉 `-Dapp.qr.secret` 与 `E2E_QR_SECRET`）、`deploy/.env.example`、`deploy/nginx/conf.d/app.conf`（去掉 `/checkin` 短链跳转） |
| 测试 | `CheckinFlowIT`、`tests/e2e/{flows,test_scenarios,loadtest,conftest}.py` |
| Agent | `agent-service/app/graph/nodes/router.py`、`llm/prompts.py` |

**兼容性**：本项目无外部客户端，移动端与管理端同仓同发，不需要灰度期。
`FA-3002` 编码**保留不回收**（错误码表是锁定资源，回收会让历史日志无法解读），
但服务端不再返回。

## 4. 风险与补偿控制

| 风险 | 评估 | 补偿 |
|---|---|---|
| 学生"知道 sessionId 就能换票"，绕过现场约束 | **中**。入口不再需要物理在场 | `join` 强制 `face_profile.status=ACTIVE`；票据 60s 一次性；三道门禁 AND 不放宽 |
| 未录脸学生彻底无法签到（原来还能走扫码后失败申诉） | 中 | 首页与签到入口明确引导"先去人脸采集"；`FA-4006` 文案指向采集页；申诉仍可用于事后补 |
| 学生转班后看到"看得到但签不了"的场次 | 低 | `active` 与 `join` 用同一个判据（`session_expected` 快照），列表里出现的场次必然换得到票 |
| 教师忘记结束会话，首页长期挂着 | 低 | `active` 过滤 `end_at > now`；过期会话由定时任务转 `CLOSED` 并补 `ABSENT` |
| 已部署环境残留 `SESSION_QR_SECRET` | 低 | compose 不再引用该变量，残留值不影响启动；上线时顺手从 `.env` 删除 |
| 旧客户端缓存了带 `tk` 的链接 | 低 | `/checkin` 忽略 `tk` 参数，只认 `sid`，旧链接仍然可用 |

## 5. 验收口径

1. 学生首页在教师发起后 **5 秒内**（WS 推送）出现该场次，点击直接进入采集页，全程无扫码。
2. `POST /mobile/sessions/{sid}/join` 不带任何 body 返回 200 + `checkinTicket`。
3. 未录脸学生 join → `FA-4006`，**不返回** `checkinTicket`。
4. 异班学生 join → `FA-3004`。
5. 会话结束后 join → `FA-3001`；重复提交 → `FA-3003`，库中仅 1 条。
6. `GET /admin/sessions/{id}/qrcode` 与 `POST /rotate-qr` 返回 404。
7. 大屏在 1920×1080 与 1366×768 下无滚动条、无二维码区域、无换码按钮。
8. `grep -r "qrToken\|QrTokenService\|SESSION_QR_SECRET"` 在 `backend-java`、`mobile-web/src`、
   `admin-web/src`、`contracts`、`tests`、`deploy` 下无业务命中（CR 文档与历史 handover 除外）。

## 6. 裁决记录

- 2026-11-20：主人确认「取消二维码，老师发起签到后学生端直接显示并刷脸」，
  并确认**同时去掉不刷脸的兜底通道**——保留它等于"知道学号就能签"，会架空整套人脸门禁。
- 2026-11-20：确认改动范围为**正式项目**（`mobile-web` + Java 后端 + 契约 + 测试），
  演示版 `workbuddy-checkin/` 不在本次范围。
