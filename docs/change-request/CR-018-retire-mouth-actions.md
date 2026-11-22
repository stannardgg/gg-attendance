# CR-018 · 停用张嘴动作

- 提出人：主人（2026-11-22 会话内指示「学生签到取消张嘴动作」）
- 提出日期：2026-11-22
- 状态：✅ **已批准并实现**
- 关联：§4.4.1 动作库 / §4.4.3 挑战规则 / §10.5 配置项 / §16.3 验收场景
- 影响工作包：WP-06（Python 活体与动作）、WP-11（移动端文案）、WP-13（E2E）

## 1. 动机

签到要求学生对着摄像头张嘴，在实际场景里是三重负担：**观感**（学生在走廊、教室门口当众张嘴）、
**配合度**（不少人不愿意做，或做得幅度不够被判失败）、**争议**（"我明明张了"无法自证）。
张嘴判据本身是可测的——嘴区 ROI 暗度有 17 倍分离度，问题不在算法，在**交互要求**。

本 CR 把嘴部动作从动作库整体撤出，只保留「靠近 / 离远」。这是一次**产品决定**，
不是可测性结论的推翻；判据代码与阈值保留，回滚成本保持为「改一行环境变量 + 重建容器」。

## 2. 方案

### 2.1 白名单收窄

| 项 | 变更前 | 变更后 |
|---|---|---|
| `FA_ACTION_WHITELIST` 默认值 | `MOUTH_ONCE,MOUTH_TWICE,MOVE_CLOSER,MOVE_AWAY` | `MOVE_CLOSER,MOVE_AWAY` |
| 随机维度 | 4 选 1 | 2 选 1 |
| `contracts/enums/actions.json` | 4 个 enabled | 2 个 enabled；`MOUTH_*` 移入 `disabled` 并注明原因 |
| `face-api.yaml` 的 `action` 枚举 | 4 值 | 2 值 |

**判据代码不动**：`MouthActionCounter`、`mouth_dark_ratio`、`MOUTH_*` 阈值、以及它们的单测
全部保留。`gates.gate_action` 的嘴部分支仍在，只是当前不可能被分派到（动作码进不来）。
这样回滚不需要 revert 代码，也不需要重新标定阈值。

### 2.2 基线要求变严（连带影响）

尺度判据是「相对基线的变化」，基线帧检不到人脸时 `baseline_scale = 0`，无论抽到哪个动作
都必然判失败。CR-016 的处理是"退回嘴部动作"（嘴部不依赖尺度）；嘴部撤出后这条路没有了。

因此 `start` 在**白名单不含任何非尺度动作**时，基线无脸直接抛 `NoFace`（`FA-4001`），
让学生把脸放进取景框重来。判据放在白名单形态上，而不是硬编码：

```python
if face is None and not any(a not in SCALE_ACTIONS for a in whitelist):
    raise NoFace("基线画面中没有检测到人脸")
```

回滚把 `MOUTH_*` 加回后，这里自动恢复「降级为序列首帧 + `baselineDegraded=true`」的旧行为。

### 2.3 `action_hint` 的报错语义

验收脚本大量用 `action_hint` 构造负例（CR-010 的开关，生产必须关闭）。
已停用的动作码如果落到「未知动作码」分支，联调第一反应会是去查拼写和环境变量。因此拆成两个分支：

| 输入 | 返回 |
|---|---|
| `MOUTH_ONCE` / `MOUTH_TWICE` | `FA-9001`，"动作已停用：MOUTH_TWICE" |
| `TURN_LEFT` 等契约外值 | `FA-2001`，"未知动作码：TURN_LEFT" |
| `MOVE_CLOSER` / `MOVE_AWAY` | 仅在 `FA_ALLOW_ACTION_HINT=1` 时采纳 |

### 2.4 验收场景的口径变化

| 场景 | 原口径 | 新口径 | 原因 |
|---|---|---|---|
| 8 | 要求张嘴两次，只张一次 → `FA-4003`，1/2 | 要求靠近，画面完全没动 → `FA-4003`，0/1 | 动作库没有 `need=2` 的动作，"只做一次"构造不出来 |
| 13 | 基线已是张嘴状态，仍判通过 | 基线已是贴近姿态，仍判通过 | 保持"基线污染不误拒"的原意，换成尺度版 |

其余场景只把通过型用例的动作从 `MOUTH_TWICE` 换成 `MOVE_CLOSER`，造帧由
`checkin_frames_scale()` 生成（同一个人脸按不同 side 重贴，前 4 帧基线、后 4 帧放大 22%）。

## 3. 影响面

| 文件 | 变更 |
|---|---|
| `face-service-python/app/config.py` | 白名单默认值收窄；嘴部阈值加"停用，保留供回滚"注释 |
| `app/challenge/service.py` | 基线无脸 → `NoFace`（条件：白名单无非尺度动作）；`RETIRED_ACTIONS` 与"动作已停用"分支；提示文案去"请张嘴" |
| `app/challenge/store.py` | `ACTION_WINDOW` / `ACTION_NEED` 保留四键并注明用途（回滚 + 存量 challenge 反序列化） |
| `deploy/docker-compose.yml` | `FA_ACTION_WHITELIST` 默认值收窄 |
| `deploy/.env` | 同上（本地验收环境；不入库） |
| `contracts/enums/actions.json` | 版本 1.1.0 → 1.2.0；`MOUTH_*` 移入 `disabled` |
| `contracts/openapi/face-api.yaml` | `action` 两处枚举收窄；`baselineFrame` 与 `baselineDegraded` 描述改写 |
| `contracts/openapi/internal.yaml` | `actionSeq` 去掉枚举、改为说明性 string（存量数据含历史值） |
| `contracts/README.md` | 动作码约束行、目录说明 |
| `mobile-web/src/shared/api/types.ts` | `FaceAction` 收窄为 2 值 |
| `mobile-web/src/shared/api/errors.ts` | 活体/动作失败文案去掉"张嘴""连续做两次" |
| `tests/e2e/synth.py` | `composite()` 新增 `side` 参数（按倍率重贴人脸） |
| `tests/e2e/flows.py` | 新增 `checkin_frames_scale()` |
| `tests/e2e/test_scenarios.py` | 场景 01/02/04/09/14 改 `MOVE_CLOSER`；08/13 按 2.4 改写；场景 07 注释更新 |
| `tests/e2e/loadtest.py` | 压测改用靠近序列 + `MOVE_CLOSER` |
| `face-service-python/tests/test_challenge_flow.py` | 基线无脸改为断言 `NoFace`；新增"随机抽样只出尺度动作"与"已停用动作码被拒"两例 |
| `项目文档.md` | §4.4.1 表格与停用说明、§4.4.3 条 1/3/5、§10.5、§16.3 场景 8/13、WP-06 现状注记、变更记录 v2.9 |
| `CHANGELOG.md` | Unreleased / Removed |

**接口形状不变**：`start` / `frame` / `finish` 的字段与门禁键名一律不动，只是 `action` 少两个取值。
`baselineDegraded` 保留（当前恒 `false`），回滚时不需要改契约。

**Java 侧零改动**：`actionSeq` 一直是透传字符串，不校验枚举（权威在 Python 白名单）。

## 4. 验证

- `pytest tests` 全量 **40 passed**（本机含模型与 CASIA；删 2 例、加 3 例）。
- 新增用例：`test_start_rejects_blank_baseline`（空白基线 → `NoFace`）、
  `test_random_pick_only_scale_actions`（12 次抽样 ⊆ {CLOSER, AWAY}）、
  `test_retired_action_hint_rejected`（`MOUTH_TWICE` → "已停用"）。
- 契约文件语法校验通过（`json.load` + `yaml.safe_load`）；`mobile-web` 的 `vue-tsc` 通过。
- 容器重建后冒烟：12 次抽样只出 `MOVE_CLOSER` / `MOVE_AWAY`；
  `action_hint=MOUTH_TWICE` 返回"动作已停用：MOUTH_TWICE"；空白基线抛 `NoFace`。
- E2E 受影响场景 **01 / 02 / 04 / 08 / 09 / 13 / 14 全过**（阶段 A 口径：
  `LIVENESS_THRESHOLD=0`，CASIA 静图活体分天花板所致）。Java 侧
  `AttendanceExportXlsxTest` 通过（`CheckinFlowIT` 仅 CI 跑）。

## 5. 已知限制

1. **防预录强度下降**。动作从 4 个组合降到 2 个：攻击者只要一段"人脸靠近"的视频，
   命中概率从 1/4 升到 1/2。活体与首末帧身份一致性仍是主防线，三道 AND 的语义未变，
   但**随机性带来的边际收益确实变薄**。这是取消张嘴的直接代价，需在真人验收时重新评估。
2. **无基线即拒**。学生必须先把脸放进取景框，`start` 才发挑战。原先那种"脸没对准也能
   先开始、后续帧补基线"的宽容路径没有了——换来的是不再出现"做完了却判失败"。
3. **场景 8 的覆盖弱化**。`need=2` 的计数逻辑（两次动作之间必须断开）不再有端到端用例，
   只剩 `MouthActionCounter` 的单测。若将来新增 `need>1` 的动作，必须补回该类场景。
4. **`ALLOW_ACTION_HINT` 仍是验收开关**。当前 `deploy/.env` 为 `1`，生产部署必须关。
   关闭后客户端无法指定动作，E2E 也随之失效——验收环境需显式开启。
5. **真人未标定**。尺度动作的 13.7σ 是合成素材结论，真人"举着手机边看边挪"的姿态、
   运动模糊、自动曝光收敛都未实测（CR-016 遗留项，本 CR 后成为唯一在用的判据）。

## 6. 回滚

```bash
# 1) 把嘴部动作加回白名单（.env 或 compose 的 ACTION_WHITELIST 均可）
ACTION_WHITELIST=MOUTH_ONCE,MOUTH_TWICE,MOVE_CLOSER,MOVE_AWAY

# 2) 必须重建容器：restart 不会重读 .env
DOCKER_BUILDKIT=0 docker compose up -d --force-recreate --no-deps face
```

回滚后行为自动恢复：基线无脸降级为首帧、`action_hint` 可指定 `MOUTH_*`、
验收场景可继续用嘴部序列。无需 revert 任何代码，也不需要迁移数据。
