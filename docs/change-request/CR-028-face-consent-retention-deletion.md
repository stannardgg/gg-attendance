# CR-028 · 人脸单独同意 v2、数据主体删除权与保留期限（合规交付）

- 提出人：主人（2026-11-23 会话内指示：「人脸信息采集单独同意（勾选框不够）、明确保留期限与删除权实现、向业主出具数据处理说明；目前只有技术侧的加密，法务合规文档缺失」）
- 提出日期：2026-11-23
- 状态：✅ **已批准并实现**（会话内指示即批准）
- 关联：§13（合规）/ CR-009（采集落库）/ CR-012（向量与备份加密）/ CR-014（一人一档）/ R9（人脸数据合规风险）
- 影响面：人脸采集链路（Java+前端）、契约、数据库迁移 V10、合规文档（新增 docs/compliance/）

## 1. 问题

CR-028 之前的合规盘点（2026-11-23）：

1. **单独同意形同虚设**：`EnrollView` 用勾选框（`van-checkbox`）+ 提交时带 `consentVersion`，勾选框与静默继续不构成 PIPL §29 要求的"单独同意"；且摄像头在进入页面时就启动——**同意之前已经在采集**；
2. **服务端零校验**：契约承诺"未同意则 400"，实现里 `writeConsent` 只写不查——`consentVersion` 由客户端自报、无任何验证；
3. **删除权没有实现**：页面文案写"可联系老师申请删除"，实际无自助通道、无撤回语义、无事件留痕；
4. **保留期限未成文**：Redis TTL（15 分钟）、备份滚动（7 天）、容器日志轮转（10m×3）散落各处，没有一份统一的保留期限文档；`audit_log` / `agent_*_log` 只进不出；
5. **归档学生向量滞留**：`StudentService.delete` 的归档分支（有考勤记录 → ARCHIVED）不删 `face_vector`/`face_profile`——识别快照虽然只发 ACTIVE 学生，但数据失去处理必要性后仍留在库中，违反最小必要。

## 2. 方案

### 2.1 单独同意 v2（勾选框 → 显式同意动作 + 服务端事件表）

| 环节 | 行为 |
|---|---|
| 文本 | 《人脸信息采集与使用告知（v2）》全文（`docs/compliance/face-consent-notice-v2.md`），要素齐全（处理者/目的/清单/期限/删除/安全/不同意后果/投诉），占位符留业主填写 |
| 前端时序 | 进入采集页**先弹同意全文、不开摄像头**；点「不同意并退出」立即返回且零落库；点「同意并开始采集」→ 调 consent 端点 → 成功后才 `startCamera()`；弹窗点遮罩不关闭 |
| 服务端记录 | 新端点 `POST /api/v1/mobile/face/consent`：校验版本 = 当前生效版本（`FaceEnrollService.CONSENT_VERSION_CURRENT = "v2"`），写 `face_consent_record` 事件（ACCEPT，只增不改）+ 刷新 `account.face_consent_at/version`；同账号同版本幂等（重复点击不洗掉首次同意时间） |
| 服务端强校验 | `POST /mobile/face/enroll`：consentVersion 必须等于当前版本 **且** 存在对应 ACCEPT 事件，否则 `FA-4010`（400）拒绝——同意不再依赖前端自报 |
| 状态可见 | `FaceStatusResp` 扩展 `consentVersion` / `consentAt`（撤回/删除后为 null），`/mobile/face/status` 与 enroll、consent 响应均携带；教师审核响应不回传同意态 |

**版本升级规则**：告知文本实质变更 → 前端 `CONSENT_VERSION` 与后端 `CONSENT_VERSION_CURRENT` 同步升版 → 旧同意不自动顺延，学生重新阅读并同意。v1（勾选框）时期的学生无事件行，重新采集时自然被引导到 v2 弹窗；已有 ACTIVE 档案不受影响。

**错误码**：新增 **`FA-4010 FACE_CONSENT_REQUIRED`（400，"未取得人脸采集单独同意"）**，四处契约同步（error-codes.json / ErrorCode.java / ErrorCodeContractTest 条数 37→38 / errors.ts×2）；仅"暂无人脸数据可删除"仍用 `FA-2001`（404，资源不存在口径）。

### 2.2 删除权实现（数据主体自助）

- 新端点 `POST /api/v1/mobile/face/data-deletion`：物理删除本人 `face_vector`（整批）+ `face_profile`，写 WITHDRAW 事件（记录被撤回的版本），清空 `account.face_consent_*`，审计 `FACE_DATA_DELETE`；
- **任意档案状态可删**（PENDING_REVIEW / REJECTED / ACTIVE），删除权不因审核流程未走完而受限；NONE 且无向量 → `FA-2001`「暂无人脸数据可删除」；
- **语义边界**：既往签到产生的考勤记录是业务凭证、不构成人脸信息处理，不随删除清除（同意书 v2 已写明）；
- 前端：采集页新增「删除我的人脸数据并撤回同意」按钮（status ≠ NONE 时可见），`showConfirmDialog` 二次确认；
- **归档同步清脸**：`StudentService.delete` 归档分支现在也物理删除向量与档案（离校学生失去处理必要性；快照本就只含 ACTIVE 学生，无功能影响），审计文案注明 CR-028。

### 2.3 保留期限成文 + 兜底清理

- 《数据处理说明（致业主）》（`docs/compliance/data-processing-statement.md`）给出完整保留期限表：原图不落盘 / 采集缓存 TTL 15min / 活体挑战 ~1min / 向量至撤回删除 / 考勤按学籍制度 / audit 365d / agent 日志 180d / 备份 7d / 容器日志 10m×3；
- 新增 `fa-system/LogRetentionJob`（每天 03:40，Asia/Shanghai）：`audit_log` 清 365 天前、`agent_query_log` / `agent_action_log` 清 180 天前（均有 `created_at` 索引）；阈值可用 `app.retention.audit-log-days` / `app.retention.agent-log-days` 覆盖（默认与 V1 注释"保留期 ≥1 年"一致）。

## 3. 影响面

| 位置 | 变更 |
|---|---|
| 迁移 | `db/migration/V10__face_consent_record.sql`（新表：id / account_id / student_id / action / consent_version / created_at，无物理外键） |
| Java | `fa-org/FaceEnrollService`（consent/deleteOwnData/enroll 强校验/状态扩展；移除 writeConsent）、`fa-org/MobileFaceController`（+2 端点）、`fa-org/ScopeDtos`（FaceConsentReq + FaceStatusResp 扩列）、`fa-org/StudentService`（reviewFace 响应置 null + 归档清脸）、`fa-system/LogRetentionJob`（新增） |
| 契约 | `java-api.yaml`：+`/mobile/face/consent`、`/mobile/face/data-deletion`；FaceStatus +2 字段；FaceConsentReq；enroll 描述改口径、x-error-codes 增 FA-2001/FA-4010。**新增错误码 `FA-4010`**：error-codes.json / ErrorCode.java / ContractTest（38）/ errors.ts×2 四处同步 |
| 前端 | `mobile-web/EnrollView.vue`（重写：同意弹窗前置 + 删除入口 + 移除勾选框 + FA-4010 回弹同意）、`MeView.vue`（+「删除人脸数据」入口，showConfirmDialog 确认）、`types.ts`（FaceStatusDetail +2 可选字段）、`errors.ts` ×2（+FA-4010） |
| 文档 | `docs/compliance/face-consent-notice-v2.md`（新）、`docs/compliance/data-processing-statement.md`（新）、本 CR、CHANGELOG、项目文档 §13.4（勾选框口径 → 单独同意 v2/自助删除/保留期限） |
| 测试 | `fa-bootstrap/FaceConsentIT`（新，2 用例：同意-删除生命周期 + 归档清脸；学生走真实激活链路，向量/档案 jdbc 直写模拟入库） |

## 4. 风险与补偿控制

| 风险 | 评估 | 补偿 |
|---|---|---|
| 旧版 App 缓存页面仍走勾选框路径提交 | 低（H5 即时加载） | enroll 服务端强校验兜底：无 v2 ACCEPT 事件一律 404 |
| 学生误删人脸数据 | 低 | 删除前 showConfirmDialog 明确后果；删后可随时重新同意采集；审计可追溯 |
| 归档清脸与业主预期不符（希望保留） | 低 | 归档学生本就不参与识别；如需恢复须重新采集。已在数据处理说明 §4 披露 |
| 同意事件表膨胀 | 极低 | 每学生每版本一行 ACCEPT + 至多一行 WITHDRAW，量级=学生数×版本数 |

## 5. 验收口径

1. 未同意直接调 enroll（含伪造 consentVersion）→ 400 `FA-4010`，库里无向量无档案无同意事件；
2. 同意弹窗点「不同意并退出」→ 退出采集页，摄像头从未启动，无任何网络写入；
3. 点「同意并开始采集」→ `face_consent_record` 新增 ACCEPT 行、`account.face_consent_at/version` 更新、摄像头随后才启动；重复点同意不产生第二行、首次时间不变；
4. 采集页与「我的」页对已有数据学生显示删除入口 → 确认后向量/档案物理消失、status 回 NONE、出现 WITHDRAW 行、`account.face_consent_*` 为 NULL、`/mobile/face/status` 的 consentVersion/consentAt 为 null；
5. 管理员删除有考勤记录的学生（归档）→ `face_vector`/`face_profile` 同步消失，审计文案含 CR-028 标记；
6. `LogRetentionJob` 手动触发 → 超 365/180 天的日志行被清理（本机可用 `SPRING_SCHEDULE` 下直接跑一次验证）；
7. （1~5 已由 `FaceConsentIT.consentAndDeletionLifecycle` / `archiveCascadesFaceData` 覆盖，学生侧走真实激活链路。）

## 6. 遗留项（同日会话内已全部清理，仅剩业主侧一项）

| 项 | 状态 |
|---|---|
| `MeView.vue` 「删除人脸数据」入口 | ✅ 已落地（showConfirmDialog 确认 + data-deletion 端点 + refreshFaceStatus） |
| 专用错误码 `FA-4010 FACE_CONSENT_REQUIRED`（400） | ✅ 已落地（四处同步：error-codes.json / ErrorCode.java / ContractTest 37→38 / errors.ts×2；Java consent 与 enroll 校验已切换，EnrollView 按码回弹同意弹窗） |
| `项目文档.md` §13.4 口径 | ✅ 已修订（勾选框口径废除 → 单独同意 v2 / 自助删除 / 保留期限 / 原图不落盘对齐 CR-012） |
| `FaceConsentIT` 集成测试 | ✅ 已落地（2 用例，见 §5 验收口径第 7 条） |
| 同意书占位符填充 | ⏳ 待业主提供学校名称/负责人/联系方式；填写后同意书即对外生效 |

## 7. 裁决记录

- 2026-11-23：主人指示完成"单独同意（勾选框不够）+ 保留期限 + 删除权实现 + 向业主出具数据处理说明"，并明确"目前只有技术侧的加密，法务合规文档缺失"。实现口径：同意 = 前置全文弹窗 + 服务端事件表 + enroll 强校验；删除 = 数据主体自助、免审批；保留期限 = 文档成文 + LogRetentionJob 兜底；加密侧 CR-012 已达标，本 CR 不重复建设（盘点结论写入数据处理说明 §5）。
- 2026-11-23 遗留清理：主人指示「把遗留项清掉，把文档转为 Word 版出具，最后提交本次改动」。四项遗留全部落地；错误码从"复用 FA-2001"升级为专用 `FA-4010`（400）， ConsentIT 用例按 FA-4010 断言。
