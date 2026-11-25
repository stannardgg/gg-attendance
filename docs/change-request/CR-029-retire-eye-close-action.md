# CR-029 · 停用闭眼动作

- 提出人：主人（2026-11-25 会话内指示「动作库中删除闭眼一秒再睁开」）
- 提出日期：2026-11-25
- 状态：✅ **已批准并实现**
- 关联：§4.4.1 动作库 / §4.4.3 挑战规则 / §10.5 配置项 / §16.3 验收场景
- 关联变更：CR-021（引入闭眼与转头）、CR-018（停用嘴部动作，本 CR 的同构先例）
- 影响工作包：WP-06（Python 活体与动作）、WP-11（移动端文案）、WP-13（E2E）

## 1. 动机

产品决定：签到不再要求学生「闭上眼睛约 1 秒，再睁开」。与 CR-018 同构——
闭眼判据本身是可测且已真机标定的（CR-021：降幅 0.178±0.065，阈值 0.06 → 3.9σ，
hold=2 于 2026-11-22 恢复），**停的是交互要求，不是可测性结论的推翻**。

## 2. 方案

### 2.1 白名单收窄

| 项 | 变更前 | 变更后 |
|---|---|---|
| `FA_ACTION_WHITELIST` 默认值 | `MOVE_CLOSER,MOVE_AWAY,EYE_CLOSE,TURN_LEFT,TURN_RIGHT` | `MOVE_CLOSER,MOVE_AWAY,TURN_LEFT,TURN_RIGHT` |
| 随机维度 | 5 选 1 | 4 选 1 |
| `contracts/enums/actions.json` | 5 个 enabled | 4 个 enabled；`EYE_CLOSE` 移入 `disabled` 并注明原因 |
| `face-api.yaml` 的 `action` 枚举 | 5 值 ×2 处 | 4 值 ×2 处 |
| `mobile-web` `FaceAction` 类型 | 5 值 | 4 值 |

**判据代码不动**：`EyeCloseCounter`、`eye_dark_ratio`、姿态一致性闸（yaw/roll 剔帧）、
`eye_*` 阈值、`EYE_ACTIONS` 分支以及它们的单测全部保留。`gates.gate_action` 的闭眼
分支仍在，只是当前不可能被分派到。`store.py` 的 `ACTION_WINDOW` / `ACTION_NEED`
保留 `EYE_CLOSE` 键：回滚只改环境变量；部署瞬间未过期的存量 challenge 仍能按原
判据跑完剩余窗口。`disguise.py` 对 `eye_roi` 的使用（墨镜判据）与闭眼动作无关，不受影响。

### 2.2 `action_hint` 的报错语义

`EYE_CLOSE` 加入 `RETIRED_ACTIONS`（与 `MOUTH_ONCE` / `MOUTH_TWICE` / `BLINK` 并列）：

| 输入 | 返回 |
|---|---|
| `EYE_CLOSE` | `FA-9001`，"动作已停用：EYE_CLOSE"（无论 `FA_ALLOW_ACTION_HINT` 开关） |
| `MOVE_CLOSER` 等白名单值 | 仅在 `FA_ALLOW_ACTION_HINT=1` 时采纳 |

### 2.3 E2E 场景口径变化

| 场景 | 原口径 | 新口径 | 原因 |
|---|---|---|---|
| 19 | 要求 `EYE_CLOSE`，闭眼 4 帧 → 判通过（同时验证眼框 ROI 跨帧锁定） | hint 已停用的 `EYE_CLOSE` → `start` 拒绝（`FA-9001`「已停用」），无考勤记录 | 闭眼不再下发，正例失去可复现入口；改为钉住 2.2 的拒绝语义 |
| 20 | 要求 `EYE_CLOSE` 但全程睁眼 → `FA-4003`，0/1 | 移除（`FA-4003` 语义由场景 21 转头不动继续覆盖） | 同上 |

`conftest._action_detectable()` 的 `_eye_detectable` 预检保留：它由
`E2E_ACTION_WHITELIST` 门控，回滚白名单后自动恢复生效，代码不改。

## 3. 影响面

| 文件 | 变更 |
|---|---|
| `face-service-python/app/config.py` | 白名单默认值收窄；`eye_*` 阈值块加「已停用，保留供回滚」注释 |
| `app/challenge/service.py` | `RETIRED_ACTIONS` 增加 `EYE_CLOSE`；模块 docstring、`INSTRUCTIONS` 注释更新（文案保留供回滚） |
| `app/challenge/store.py` | `EYE_ACTIONS` / `ACTION_WINDOW` / `ACTION_NEED` 注释更新（保留全部键） |
| `deploy/docker-compose.yml` | `FA_ACTION_WHITELIST` 与 `E2E_ACTION_WHITELIST` 默认值收窄 |
| `contracts/enums/actions.json` | 版本 1.3.1 → 1.4.0；`EYE_CLOSE` 移入 `disabled` |
| `contracts/openapi/face-api.yaml` | `action` 两处枚举收窄，判据描述改两类 |
| `contracts/openapi/internal.yaml` | `actionSeq` 说明更新（CR-018 + CR-029 两次停用） |
| `contracts/README.md` | 动作码约束行、目录说明 |
| `mobile-web/src/shared/api/types.ts` | `FaceAction` 收窄为 4 值 |
| `mobile-web/src/shared/api/errors.ts` | `action_count` 处置建议去掉「闭眼约 1 秒」 |
| `tests/e2e/conftest.py` | `E2E_ACTION_WHITELIST` 默认值收窄（`_eye_detectable` 保留，门控生效） |
| `tests/e2e/test_scenarios.py` | 场景 19 按 2.3 改写、场景 20 移除；`do_checkin` 注释更新 |
| `face-service-python/tests/test_challenge_flow.py` | 抽样断言改 4 动作；`test_retired_action_hint_rejected` 增加 `EYE_CLOSE` 参数；`test_new_action_instructions_and_windows` 去闭眼分支 |
| `CHANGELOG.md` | Unreleased / Removed |

**接口形状不变**：`start` / `frame` / `finish` 的字段与门禁键名一律不动，只是 `action` 少一个取值。
**Java 侧零改动**：`actionSeq` 一直是透传字符串，不校验枚举（权威在 Python 白名单）。
**Agent 侧零改动**：工具不下发动作码，随机动作由 face 服务决定。

## 4. 验证

- `pytest tests` 全量 **80 passed**（本机含模型与 CASIA）。闭眼计数器/姿态闸/
  可做性的单测保留；抽样断言改 4 动作；retired hint 参数化 +1
  （`EYE_CLOSE` → "动作已停用"）；新增 HTTP 层用例
  `test_retired_action_hint_rejected_api`（`action=EYE_CLOSE` → 400 +
  `FA-9001` + "已停用"），e2e 场景 19 断言的就是这条映射路径。
- compose 冒烟：face 镜像重建并 `up -d --force-recreate` 后实测容器环境
  `FA_ACTION_WHITELIST=MOVE_CLOSER,MOVE_AWAY,TURN_LEFT,TURN_RIGHT`。
- E2E **当前无法端到端执行，阻塞点为 main 既有缺口、与本 CR 无关**：
  CR-028 的 enroll 同意书强校验（`FA-4010`）已在 main，但 e2e 采集流程
  （`flows.enroll_face`）未接同意书步骤，world fixture 在「第 1 名学生采集」
  处即失败（20 个场景全部 ERROR 于 setup，错误统一为 FA-4010；
  `feat/cr028-face-compliance` 分支同样未含 e2e 适配）。E2E 适配应随
  CR-028 收尾单独处理。

## 5. 已知限制

1. **防预录随机性变薄**。5 选 1 → 4 选 1：攻击者一段预备视频的命中概率从 1/5 升到
   1/4。活体与首末帧身份一致性仍是主防线，三道 AND 语义未变。
2. **眼框暗度判据的端到端覆盖归零**。判据只剩单测（计数器/姿态闸/可做性），
   E2E 正负例均移除。回滚后若需回归，重跑 `probe_action.py --eye` 标定即可。
3. **待同步项（未合并分支）**：`feat/cookie-login-lock` 上的
   `scripts/eval_active_liveness.py` 的 `ACTIONS` 元组仍含 `EYE_CLOSE`，
   `docs/verification/active-liveness-protocol.md` 同样提及——该分支合并时需
   把闭眼从评测动作集中移除，否则对已停用动作发 hint 会在 start 阶段失败。
4. **项目文档 §4.4 / §16.3 补记**：并行会话正占用 `项目文档.md`（工作区有未提交
   改动），本 CR 落地后单独补记（同 CR-028 §6 先例）。

## 6. 回滚

```bash
# 1) 把闭眼动作加回白名单（.env 或 compose 的 ACTION_WHITELIST 均可）
ACTION_WHITELIST=MOVE_CLOSER,MOVE_AWAY,EYE_CLOSE,TURN_LEFT,TURN_RIGHT

# 2) 必须重建容器：restart 不会重读 .env
DOCKER_BUILDKIT=0 docker compose up -d --force-recreate --no-deps face
```

回滚后行为自动恢复：闭眼重新参与抽签、`action_hint` 可指定 `EYE_CLOSE`、
e2e 预检恢复眼区筛查、场景 19/20 旧口径可用 `git revert` 本 PR 恢复。
无需重新标定阈值，判据代码从未移除。
