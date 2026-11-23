# CR-025 · 活体伪装/遮挡识别（口罩、墨镜、帽子）

- 提出人：主人（2026-11-22 会话内指示「增加活体伪装识别功能（化妆、戴帽子等）」）
- 提出日期：2026-11-22
- 状态：✅ **已批准并实现**
- 关联：§4.4.4 门禁 / §10.5 配置项 / §13.2 误拒率 / contracts FA-4009 / CR-021（106 点关键点复用）
- 影响工作包：WP-06（Python 活体）、WP-11（移动端文案）、契约三处

## 1. 动机

现有门禁链（活体/身份/动作/被动活体/1:N）拦得住「不是本人」，但拦不住
「本人带着伪装物糊弄」：戴口罩/墨镜/帽子签到时，学生会拿到一句
`FA-4001 未检测到人脸` 或 `FA-4005 人脸不匹配`——**报错与原因无关**，
学生只会反复重试，教师端申诉归因也说不清。本 CR 在采集管线里加一道
**遮挡识别**，给出明确指令（「请摘下口罩」），并让失败可归因。

化妆边界：**普通化妆（眼影/口红/粉底）不在拦截范围**。理由：
1. 化妆不影响考勤真实性（识别门禁兜底身份），拦它必然误拒（§13.2）；
2. 无判据能在不误拒的前提下区分「浓妆」与「深肤色/阴影」。
只有**整脸涂装/面具**这类让活体与识别同时失效的极端遮挡才属于本 CR
范围（由下述判据的「口罩」分支兜住）。

## 2. 方案

### 2.1 判据：对齐空间上的统计量（零新增模型）

全部在 192 对齐空间（`landmarker().align_space`，帧管线已计算）上完成，
不新增模型调用，单帧开销 <1ms。区域坐标在 start 锁定（106 点关键点的
眼框 `eye_roi` 与嘴区 `roi_*`，均为 192 空间整数），后续帧直接复用——
与现有动作 ROI 锁定机制完全同构，且规避了第三轮实验发现的
「遮挡导致关键点漂移污染统计」问题。

每个判据都有明确物理含义，阈值全部来自 2026-11-22 五轮 CASIA 标定实验
（40 身份，JPEG q90 传输链路，`/tmp/probe_disguise_r*.py` 可复跑）：

| 类别 | 判据 | 干净脸分布 | 遮挡分布 | 阈值 |
|---|---|---|---|---|
| 口罩 | 下半脸（嘴区外扩，必盖区）YCrCb 肤色占比 ≤ 0.10 **或** 嘴区灰度 std ≤ 24（均质布料） | skin ≥ 0.113 / std ≥ 30 | 浅色织物 90% ≤ 0.10；平色块 ≤ 22 | `mask_skin_max=0.10` / `mouth_flat_max=24` |
| 墨镜 | 两眼框灰度 std ≤ 15 **且** 相对亮度（眼框均值/脸中心均值）≤ 0.50 | std ≥ 27.2 / rel ≥ 0.72 | 纹理镜面 ≤ 13.7 / ≤ 0.21 | `eye_std_max=15` / `eye_rel_max=0.50` |
| 帽子 | 额头带（crop 顶 10% ~ 眼框上缘）灰度 std ≤ 16（布料级平坦） | std ≥ 36.3 | 平布/针织代理 ≤ 24.6 | `forehead_std_max=16` |

设计取舍（都来自实测教训，勿凭感觉改）：

1. **不做肤色硬编码阈值**：肤色占比判据只用于「下半脸必盖区」，且阈值取
   干净脸实测最低值（0.113）之下——深肤色/暖光下判据整体向「不拦」侧退化，
   不会误拒。
2. **墨镜必须「均匀 + 暗」双条件**：闭眼时眼睑也是低 std（实测代理最高 19.4），
   但眼睑是皮肤色（rel ≥ 0.97），亮度条件把它排除——闭眼动作与墨镜判据不冲突。
   透明眼镜不触发（镜片透明，std/亮度不变）。
3. **帽子只拦「布料级平坦」**：std 阈值 16 远低于干净额头最低值（36.3），
   有纹理的物体（头发、针织帽）大概率逃逸——**宁可放过，不可错拦**。

### 2.2 已知盲区（业主知悉）

- **棕色/深色织物口罩**：棕 = 暗橙 = 肤色同色度（Cr/Cb 落在肤色区间），
  肤色判据失效；织物纹理又让 std 判据失效。此类遮挡由匹配门禁兜底
  （下半脸被遮 → ArcFace 相似度下降 → FA-4005），只是文案不精确。
- **齐刘海/针织帽**：与「布料级平坦」同分布，`forehead_std_max` 下方
  无法区分。上线后若出现误拒案例：调高 `FA_DISGUISE_FOREHEAD_STD_MAX`
  或 `FA_DISGUISE_HAT=0` 单独关闭帽子分支（不影响口罩/墨镜）。
- 极端遮挡（整脸贴纸等）会让 SCRFD 直接检不出 → 走 FA-4001 无脸路径，
  属预期行为（第一轮实验：粗糙整块贴图 30 张里 28 张直接检不出）。

### 2.3 接入点（三处，与 multi_face/no_face 中止路径同构）

| 接入点 | 行为 |
|---|---|
| `start` | 基线帧评估，命中即 `DisguiseError` → **400 FA-4009**（带种类文案），不消耗重试次数（与基线多脸拒绝同构） |
| `frame` | 每帧评估（复用 aligned crop 与锁定的 eye_roi/roi，零模型调用），命中记入 `occl_total/occl_run/occl_kinds`；连续 ≥3 帧命中 → 关挑战 + `_record_failure(st, "disguise")` + FA-4009；未达中止阈值时 frame hint 提示摘除（覆盖动作提示） |
| `finish` | 第 5 道门禁 `gate_disguise`：遮挡帧占比（occl_total/有脸帧数）≤ 0.50，逐项回传 value/threshold；`rejectGate="disguise"` 计入失败（CR-023 计数） |

rejectGate 检查顺序：`disguise` 排最前——戴着口罩时 match/liveness 的失败
都是它的下游症状，归因要从最外因开始。门禁链（CR-024 后现状）：
**disguise → liveness → identity → actionCount → passive → match**。

state 新增字段（Redis JSON，向后兼容：旧记录缺字段用默认值）：
`occl_run`（连续遮挡帧）、`occl_total`（遮挡总帧）、`occl_kinds`（出现过的类别）。

### 2.4 契约变更

| 项 | 变更 |
|---|---|
| `contracts/enums/error-codes.json` | 新增 `FA-4009`（400，Face，「检测到面部遮挡或伪装物」），codes 35 → 36 |
| `backend-java ErrorCode` | 新增 `FACE_DISGUISED`，`ErrorCodeContractTest` 条数 35 → 36 |
| `contracts/openapi/face-api.yaml` | `rejectGate` 枚举加 `disguise`；`gates` 加 `disguise: GateResult`；start/frame/finish 的 `x-error-codes` 加 FA-4009 |
| `contracts/openapi/internal.yaml` | `CheckinAttemptFailReq.rejectGate` 说明加 `disguise`（仅排障记录，无需枚举校验） |
| `mobile-web errors.ts` | `FA-4009` 文案 + `GATE_LABEL/GATE_ADVICE.disguise` + `gateCode()` 映射；admin-web 同步 |
| Agent `DraftTools.java` | `disguise` 归因文案「面部有遮挡或伪装物」 |

### 2.5 配置（§10.5 FACE 组，全部 editable=0，标定脚本维护）

| 键 | 默认 | 含义 |
|---|---|---|
| `DISGUISE_ENABLED` | 1 | 总开关（FA_DISGUISE_ENABLED） |
| `DISGUISE_HAT` | 1 | 帽子分支独立开关（误拒逃生门） |
| `DISGUISE_MASK_SKIN_MAX` | 0.10 | 下半脸肤色占比上限 |
| `DISGUISE_MOUTH_FLAT_MAX` | 24 | 嘴区灰度 std 上限（均质布料） |
| `DISGUISE_EYE_STD_MAX` | 15 | 眼框灰度 std 上限 |
| `DISGUISE_EYE_REL_MAX` | 0.50 | 眼框相对亮度上限 |
| `DISGUISE_FOREHEAD_STD_MAX` | 16 | 额头带灰度 std 上限 |
| `DISGUISE_OCCL_FRAMES` | 3 | 连续遮挡中止帧数（与 no_face/multi_face 同构） |
| `DISGUISE_OCCL_FRAME_MAX` | 0.50 | finish 门禁：遮挡帧占比上限 |

## 3. 验收

| 项 | 通过标准 | 结果 |
|---|---|---|
| BPCER（误拒） | CASIA 40 身份干净脸 0 误报 | ✅ 0/40 |
| 口罩拦截 | 浅色/白色织物口罩端到端可拦 | ✅（skin 判据） |
| 墨镜拦截 | 纹理镜面可拦；闭眼/透明镜不触发 | ✅（std+亮度双条件） |
| 帽子拦截 | 平坦布料可拦；刘海代理不触发 | ✅（std=16 阈值下刘海 std≥36） |
| 单测 | tests/test_disguise.py 全绿 | ✅ |
| 契约 | error-codes 四处同步一致 | ✅ |
