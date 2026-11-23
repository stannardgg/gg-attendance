# Changelog

本文件记录可交付版本的变更。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

批次完成标记（`batch-N-done`）与版本 tag 是两套，不写入本文件；本文件只记**可交付版本**。

## [Unreleased]

## [1.4.1] - 2026-11-23

### Added
- **开课教师同步授权（CR-027，课程管理 = 教师授权的授予入口）**：课程管理
  选/改「开课教师」保存时，后端幂等写入 `teacher_scope(COURSE)`（已存在
  跳过、教师不存在 FA-2001 拒绝、审计记 `SCOPE_GRANT`），教师端无需再去
  授权范围手动授权、登录即见该课程；撤销仍走「教师与授权 → 授权范围」。
  权限口径：管理员写 owner 才产生授权；TEACHER 建课 owner 强制本人（与
  Agent `apply_owner_teacher` 一致，建课即自见）、改课 owner 仅作归属标注
  不产生授权（教师只能动授权范围内的课程，不构成新授权来源）。前端两处
  提示文案同步改写（课程表单 + 授权范围抽屉）。OrgIT 增
  `ownerTeacherSyncsScope`（建课即见/重复保存幂等/幽灵教师 FA-2001/改
  owner 新教师立即可见）。
- **课程授权学生（CR-026）**：课程管理端新增「授权学生」——选班级、选学生后
  把课程单独授权给该学生（跨班选修、转班补授场景），发起签到时并入应到
  快照。D7 主体不动（`course` 仍无 classId、班级课程互不引用、选课表
  `course_student` 仍不建）：新表 `course_student_grant`（V9）是**授权表**
  （与 `teacher_scope` 同类），班级名单仍是默认应到，授权学生是**追加**
  （UNION 去重）；撤销=物理删除；授权变动只影响新会话，已存续会话不追溯
  （CR-001 快照语义）。端点挂课程子资源
  `GET/POST /api/v1/admin/courses/{courseId}/grants`、
  `DELETE .../grants/{studentId}`（TEACHER+ADMIN）；教师侧双重数据范围校验
  （课程 requireCourse + 学生当前班级 requireClass），管理员不受限；学生
  不在读 FA-2001、重复授权新错误码 **FA-2008**（409，四处契约同步）。
  课程/学生物理删除分支级联清理授权行。学生端无改动——「可签会话」以
  `session_expected` 为判据，授权并入快照后自然可见可签。
- **Agent 负责教师口径按登录身份落实（教师本人自动带、管理员须显式给 ID）**：
  教师对话建/改班级、课程说「负责教师张三」时，Agent 不再反问教师 ID——
  catalog（`/internal/agent/tools/catalog`）新增 `identity`（teacherId + role，
  每轮现算，不用界面登录快照），Agent 三层落实：① system prompt 与
  身份消息写死口径（教师=本人，管理员=必须显式提供数字 ID、未提不传）；
  ② `apply_owner_teacher` 在 tool_node 与 commit 两处对
  `create/update_class/course` 教师身份强制 `ownerTeacherId=本人`（确定性
  兜底，模型填了别人也覆盖；管理员通道不注入），预览与提交同规则归一，
  paramsHash 一致；③ Java 预览把 ownerTeacherId 渲染为「姓名（#ID）」并
  在预览阶段校验教师存在（悬挂 ID 预览即拒，不等到确认后失败）。契约
  `internal.yaml` 同步 `identity` 字段；AgentIT 断言管理员/教师身份下发。
- **活体伪装/遮挡识别（口罩/墨镜/帽子，CR-025，默认开）**：新增
  `app/challenge/disguise.py`，在 192 对齐空间上判「脸上有没有遮挡物」——
  此前戴口罩签到只会拿到 FA-4001（检不出）或 FA-4005（不匹配），报错与
  原因无关。三类无模型统计判据（单帧 <1ms，零新增模型）：口罩=下半脸
  必盖区 YCrCb 肤色占比 ≤0.10（干净脸实测 min 0.21）；墨镜=眼框「均匀+暗」
  双条件（std ≤15 且 相对亮度 ≤0.50，闭眼/透明镜不触发）；帽子=额头带
  「布料级平坦」（std ≤16，齐刘海/针织帽向不拦侧退化，独立开关
  `FA_DISGUISE_HAT=0` 逃生）。三处接入：start 基线命中即拒（FA-4009 带
  摘除指令，不消耗重试）、frame 连续 3 帧命中中止（计入 CR-023 失败）、
  finish 遮挡帧占比 ≤0.50 门禁；`rejectGate=disguise` 排归因最前（戴口罩
  时 match/liveness 的失败都是它的下游症状）。已知盲区：棕色织物口罩
  （与肤色同色度）由匹配门禁兜底。★ 过程中修正 `MOUTH_LM_IDS`（68~104
  混入右眼与眉毛，15/15 身份实测嘴框顶漂到眉毛高度；修正为 52~70 唇区），
  「嘴区 std 判布料」分支因该错误在实验中幸存、实测闭唇 std 23.95 天然
  平坦而废除——mask 判据只留肤色单通道。BPCER：CASIA 40 身份干净脸
  0/40 误拒；APCER：软口罩 start/frame 双路径拦截，硬口罩走 FA-4001
  （检测层先挡，预期行为）。契约：FA-4009 四处同步、rejectGate/gates 增
  `disguise`、前端 GATE_ADVICE/LABEL 补 disguise 与 passive。
- **被动活体线索接线（摩尔纹 / 屏幕反光 / 频域峰值，默认关）**：新增
  `app/challenge/passive.py`，逐帧计算三族无模型特征——非轴向频域孤立峰
  （剔除 JPEG 8×8 轴向谱峰后的重采样痕迹）、高频能量占比、整帧低饱和过曝
  白斑；检测框原生分辨率上计算（任何插值缩放都会伪造重采样痕迹）。特征
  无论开关与否都随 finish 全量落日志（每场 4 个浮点数，不落原图）——生产
  流量免费积累真实标定语料。`gate_passive` 作为第 4 道门禁接入 AND 链
  （只否决不放行；周期性证据须有高频/过曝独立旁证，单证据不否决），
  `PASSIVE_GATE=1` 开启；**默认关闭**：三轮合成标定（对齐空间/整帧/原生
  裁剪）全部零区分度——bicubic 重采样链复现不了屏幕摩尔纹的真实物理，
  按项目铁律阈值必须真机采集实测（协议见 `scripts/calibrate_passive.py`）。
  契约：`rejectGate` 枚举与 `gates` 增加 `passive`；Agent 归因文案同步。
- **动作门禁反向否决与姿态闸（相反动作误放行修复）**：验收发现「提示左转
  却右转」「提示闭眼却转头」仍可通过。根因不是方向判据缺失（计数器带符号
  且有单测），而是 hold=1 单帧噪声（转头滚转残余实测最大 0.108，距阈值
  0.12 仅 1σ）与固定眼框 ROI 漂移（头部移动把虹膜搬出 ROI，暗度下降被误读
  成闭眼）。修复：转头/尺度计数器增加**反向达标段追踪**，反向段存在即否决
  （正向做对时反向段物理上不会出现，不增误拒）；闭眼判据加**姿态一致性闸**
  （偏航 Δ>0.10 或滚转 Δ>0.26rad 的帧剔除，全部超界判未完成；部署前存量
  challenge 跳过闸门）。

- **Agent 跨轮会话记忆（「确认」失忆修复）**：上一轮模型说「回复『确认』我就提交」，
  用户回「确认」时模型却答「没有待确认的操作记录」。根因：每轮 `new_state` 重置
  全部图状态，LLM 报文里只有当前这一句，模型从未见过自己的上一轮。修复：调用方
  （Streamlit）在 append 当前消息**之前**取 prior 消息，经
  `AgentSession.ask(history=…)` 注入 state；`_llm_plan` 与闲聊分支把最近 8 条
  （单条截 300 字、旧→新）插到当前提问之前，附「结合上一轮理解确认/补充，信息齐
  就立即调工具取预览」的用法说明，裁剪统一在 `common.llm_history_messages`。配套
  收紧系统提示词：禁止「回复『确认』我就提交」式纯文本承诺——文字承诺不会生成
  确认卡，用户回复后无从执行（事故第一环）。回归测试 `tests/test_history.py`。

### Changed
- **「归属 ≠ 授权」显性化（班级/课程「负责教师」不等于授权）**：教师可见范围
  一直只由 `teacher_scope`（教师管理 → 授权范围）决定，「负责教师/开课教师」
  只是归属标注字段（`owner_teacher_id`），但极易被误当成授权入口——
  Agent 或管理员建班时带了负责教师，教师端依然看不到（授权空集）。
  行为不变，只补提示：admin-web 班级/课程表单教师字段下方、教师管理
  「授权范围」抽屉顶部新增说明文案；项目文档 §3.1 增补「负责教师不是授权」、
  §8.4.3 增补固定提示的交互约定。
- **恢复闭眼/转头 hold=2（同日撤销 hold=1 修正）**：前置优化把服务端单帧
  从 ~800ms 压到 ~300ms——检测输入 640→320（上传帧本来就是 320×240，旧值
  等于放大一倍再检测，纯浪费，det 460ms→~130ms，`FA_DET_SIZE` 可回退）+
  隔帧 ArcFace（260ms→均值 ~130ms，身份门禁/1:N 用首末帧不受影响；转头
  动作保持逐帧跑 ArcFace，frontal_emb 须在每帧上找最正脸）。服务端消化率
  ~3fps 后「保持约 1 秒」落 2~3 帧，hold=2 的时序可达性成立，单帧噪声面
  由反向否决 + 姿态闸兜底。`EYE_HOLD_FRAMES`/`TURN_HOLD_FRAMES` 默认值
  1→2（`FA_EYE_HOLD_FRAMES`/`FA_TURN_HOLD_FRAMES` 可覆盖）；文案与窗口
  不变（契约 actions.json 1.3.1 保持）。⚠️ 已入库向量按 det-640 对齐计算，
  检测输入变化会使对齐有亚像素级偏移，测试环境建议重跑 `seed_casia.py`
  刷新向量后再验收相似度。
- **识别失败三次后锁定本场签到（CR-023）**：此前「试过 3 次」只是采集页的本地计数，
  刷新即清零；关掉页面回首页，场次仍显示「待签到」，可以无限重试。现在失败计数
  落库（`checkin_attempt`，V7 迁移），Python 判定失败后经内部接口上报 Java——
  join 达到上限以 `FA-3005`（429）拒绝，`sessions/active` 新增 `retryLeft`，
  首页/入口页把该场次显示为「识别失败」且点击直接带去申诉页；Python `start`
  也查计数，旧票据（60s 寿命）偷跑第 4 次同样被拒。`rejectGate=commit`
  （Java 不可用）不计失败：基础设施故障不转嫁给 学生。附带修正
  `sys_config.CHECKIN_MAX_RETRY` 种子值 5 → 3（与 §4.4.3、FA-3005 note 对齐）。
- **学生密码修改走「申请 → 审核」（CR-024）**：学生忘了密码此前只能线下找管理员，
  改动也没有痕迹。学生「我的」页新增「修改密码」入口：提交申请（验当前密码，
  防手机被他人顺手改密）→ 管理员端「学生管理 → 密码申请」页签审核（红点计数、
  驳回必填理由）→ 通过才写 `account.password_hash` 并 `credential_version +1`，
  旧密码与已签发会话立即失效。密码只存 BCrypt 哈希且仅审核前暂存，
  通过/驳回/撤回后立即清空；同一学生仅一条 PENDING（`student_profile_request`，
  V8 迁移，生成列 `uk_spr_pending` 兜底）；学号是登录名，不在可申请范围。
  接口：`/api/v1/student/profile-requests`（自助）与
  `/api/v1/admin/student-profile-requests`（审核）。

### Fixed
- **Agent 识别不了「新建课程/班级」**：router 早把「新建课程」判成写操作意图，
  planner 却只有"发起签到/改名"两个分支，句子一律掉进「需要先指定具体对象」的
  兜底文案——业主发「新建课程 AABB 名称：离散数学，学期2022」建不了课。
  新增 `_plan_create_entity`：解析编码/名称/学期（年级/专业）标签字段，缺失给例句
  反问、学期裸年份归一化为学年（2022 → 2022-2023-1，确认卡可见）、格式非法当面
  反问不静默丢参；齐了直接取 `create_course` / `create_class` L2 预览，负责人仍由
  `apply_owner_teacher` 按登录身份兜底。问句（「为什么要新建课程」）不弹确认卡。
- **闭眼/转头真机识别失败（CR-021 采样修正）**：前端 4fps 采集但服务端单帧实测
  ~800ms（SCRFD 460ms + ArcFace 260ms），串行上传后有效入库仅 ~1.25fps——
  「闭眼约 1 秒」只有 1~2 帧落窗，「转头保持半秒」常态 0 帧（0.5s < 0.8s 帧距），
  `HOLD_FRAMES=2` 时学生按文案做也判不过。改 `EYE_HOLD_FRAMES` / `TURN_HOLD_FRAMES`
  为 1（闭眼阈值 3.9σ / 转头 11.5σ，单帧误判率可忽略；动作随机抽签下预录无法预知
  动作码）、`WINDOW_MS_TURN` 2500→3000、转头文案改「保持约 1 秒」；
  契约 `actions.json` 1.3.1。E2E 全绿是同步逐帧 POST 掩盖了采样稀疏——判据灵敏度
  与时序可达性须分开验收（§4.4.3-3a）。
- **改密后新令牌可能被旧快照拦下**：`AccountRoleResolver.invalidate` 原先在事务提交**前**
  清缓存，此刻进来的并发读会从库里读到旧 `credential_version` 并写回缓存，提交后的新值
  被压住最多 30 秒——表现为「刚登录拿到的新令牌被判为已过期」。现在失效注册到
  `afterCompletion`，提交后再删；没有事务的调用路径（单测）行为不变。
  同时把 `OrgIT.teacherProfileRequestClearField` 的收尾断言改成自诊断形式（先断言 200，
  再断言 `department` 为 null，均带响应体），避免再次出现「只报 true/false、看不出是
  没清空还是 401」的排查成本。
- **场景 19 随抽到的造帧身份时红时绿**：`conftest._action_detectable()` 原先只筛
  「合成张嘴能否判出」，CR-021 加入闭眼后没人管，而 CASIA 中确有身份的「肤色覆盖眼睑」
  做不出要求的降幅（实测 9 次红 3 次）。预检改成按 `FA_ACTION_WHITELIST` 决定筛哪几族，
  且**必须把画面过一遍 JPEG q90 传输编码再量**——服务端量的是压缩帧，压缩抹平肤色块会
  让降幅小 0.01~0.02。补完后 40 个身份剔除 4 个（010/017/024/026）。
- **场景 06/07 的无考勤断言停在 CR-015 之前**：CR-015 起「签到失败可申诉」要求返回
  200 + `status=null` + `canAppeal=true`，两处仍在断言 404（只在阶段 B 跑，一直没翻出来）。
  抽成 `flows.assert_no_attendance()` 统一口径。
- **门禁判失败缺少逐帧依据**：`gate_action` 判失败时补打 `闭眼未计数` / `转头未计数`
  （逐帧值 + 基线），误拒申诉能直接回答「差多少、哪一帧没跟上」；成功路径不打印。
  探针 `probe_action.py --eye --wire` 可按上传口径复现。

## [1.4.0] - 2026-11-22

### Added
- **签到动作库扩到 5 个：闭眼 + 左右转头**（CR-021）。原来只有「靠近 / 离远」两个
  位移类动作，一段举着手机靠近的预录视频命中概率 1/2。新增三类与位移正交的动作：
  `EYE_CLOSE`（眼框暗像素占比相对基线下降 ≥0.06，静止噪声 3.9σ）、
  `TURN_LEFT` / `TURN_RIGHT`（SCRFD 5 点偏航代理相对基线变化 ≥0.12，≈24°，
  静止噪声 11.5σ）。「眨眼」按主人确认改为「闭眼保持约 1 秒」——4fps 采集抓不住
  100~300ms 的眨眼，是采样定理问题，`BLINK` 码不恢复。眼框用 106 点眼球轮廓
  （33~42 / 87~96）定位、判定仍走暗度统计；转头用 `2d106det` 之外的 5 点检测头，
  不违反「关键点只用于定位与活体」的全局约束。新增可做性门槛（眼区基线/瞳距、
  偏头/贴边）与「最正脸帧」特征回填，避免侧脸末帧误判 `FA-4002`。
  契约 `actions.json` 1.3.0、`face-api.yaml` / `internal.yaml` / `FaceAction` 同步；
  验收新增场景 19/20/21。
- **智能助手可以导出考勤 Excel**（CR-020）：新增 L0 工具 `export_attendance`，复用
  `AttendanceQueryService.exportXlsxFile`，与管理端「导出 Excel」同一段 SQL、同一排序、
  同一套缺勤红底样式。文件以 `contentBase64` 返回，由 Streamlit 渲染下载按钮；
  单次上限约 3 MB 原文件，超限会要求缩小日期范围而不是静默截断。
  一句「导出近两天考勤记录」即可拿到 `考勤记录_2026-11-21_2026-11-22.xlsx`。
- **教师资料修改申请与审核**（CR-019）：教师改不了 `teacher` 表（姓名/院系/职称是
  ADMIN 独占能力），但资料有误时只能线下找管理员，改动没有「谁申请、谁批准」的痕迹。
  新增 `teacher_profile_request`（V5）与两组端点：教师 `GET/POST
  /api/v1/teacher/profile-requests`、撤回；管理员 `GET
  /api/v1/admin/teacher-profile-requests`、`POST .../{id}/handle`。通过时把申请值写入
  `teacher` 并把**实时改前/改后**写进 `audit_log`；驳回必须写理由。同一教师同时只允许
  一条 `PENDING`（`uk_tpr_pending` 生成列），冲突统一 `FA-2007`。
  工号仍是登录名，不可申请修改；密码重置不属于"资料"。管理端「教师与授权」新增
  「账号与资料申请」标签，教师端新增「我的资料」页（仅 TEACHER 可见）。
  （登录用户名与密码的自助变更随后由 CR-022 纳入同一条链。）
- **教师可申请修改登录用户名与密码，管理员审核后生效**（CR-022，V6）：
  `teacher_profile_request` 增加 `old_username` / `new_username` /
  `password_change` / `new_password_hash`（**仅暂存**），提交端点接受
  `username` / `currentPassword` / `newPassword`。三个要点：
  ① 改用户名或密码必须先通过当前密码校验，防止有人趁教师电脑没锁把账号换掉；
  ② 密码在**提交时**就做 BCrypt，明文只存在于那一次请求的内存里——通过后写入
  `account.password_hash` 并立即清空暂存列，驳回/撤回同样清空，明文与哈希都不进
  日志、审计与响应体；③ 登录名双写 `teacher.teacher_no` 与 `account.username`，
  提交时预检唯一性、审核时再检一次（防止期间被占），冲突统一 `FA-2007`。
  新增 `account.credential_version` 并进入 JWT 的 `cv` claim：改密或改登录名后
  该账号**此前签发的全部令牌立即 401**，不依赖 Redis 黑名单；升级前签发的令牌
  没有 `cv`，按 0 处理，不会被误杀。
  管理员侧 `PUT /admin/teachers/{id}` 同时支持 `password` 直接重置（教师忘记密码时
  填不出当前密码，申请链路走不通，必须留这条兜底），同样 `cv+1` 并记审计。
- `GET /api/v1/teacher/profile`：教师查看本人档案（含院系/职称），教师 id 取自令牌，
  不接受请求参数——避免变成"按 id 查任意教师"的越权口子。
- **智能助手支持历史会话**：侧栏按账号列出最近会话（Redis 独立前缀，TTL 7 天、
  最多 20 条），点击即恢复消息记录与产物，并接着原 `thread_id` 继续追问；刷新页面
  不再从零开始。导出的 xlsx 作为附件挂在产生它的那条消息上，历史恢复后下载按钮仍可用。
- **班级与课程名称支持口语化匹配**（`agent-service/app/tools/naming.py`）：
  「高数」=「高等数学」、「大数据一班」=「大数据1班」，也认课程编码。匹配分四档，
  只有最后一档算"猜"，因此加了三道闸——数字必须整段命中（`2` 不匹配 `2024`）、
  首字必须相同、**同分即歧义交回反问**，绝不替老师选（选错就是给别的班发了签到）。

### Changed
- **「发起签到」不再靠一条正则猜语序**：老师会说「给大数据一班发起签到」「给大数据一班
  高数课程发起签到」「高数，大数据1班，开个签到」，课程在动词前、省略课程、带「课程」
  后缀都出现过，正则越写越长且互相打架（还会把「大数据1班高数签到」截成学生名
  「班高数」）。现改为词表驱动 + 库里名字反查：先剥口语填充词，再按班级/课程候选名
  在句中的位置与后缀判定；只有「班名 + 签到、且没有任何查询/统计词」时才当作写操作，
  宁可少认也不抢查询。
- **导出的考勤 Excel 不再带人脸分值**：`相似度 / 活体 / 身份` 三列从文件里移除，
  只剩「学号、姓名、班级、课程、场次、状态、签到时间、调整原因」8 列。这三个分值是排障
  与申诉裁定的内部依据，进到教师、教务手里的表就会被当成「按概率判定签到是否有效」
  （出现过拿 0.63 质问「为什么这也算签到成功」），且分值随模型换版失去可比性。
  管理端列表仍显示三项——需要看分数的是处理申诉的人，不是在 Excel 里对账的人；
  行范围与排序不变，管理端按钮与智能助手 `export_attendance` 共用同一段导出代码，
  因此两处同时生效。演示版「导出 CSV」同步去掉相似度列。
- **鉴权改为以数据库中的角色为准**（`AccountRoleResolver`，30 秒缓存 + 写路径主动失效）。
  令牌里的 `role` 只是签发时的快照，有效期 30 分钟；一旦库里角色与令牌不一致，
  `/auth/me`（读库）会把界面渲染成管理员，而写接口（读令牌）回 `FA-1003`，
  用户只会看到「权限不足」。现在令牌只证明"你是谁"，角色与账号状态每次向库里要，
  顺带把「停用教师后旧令牌仍能用 30 分钟」的窗口压到 30 秒内。
- **签到不再要求张嘴**（CR-018）：动作白名单收窄为 `MOVE_CLOSER` / `MOVE_AWAY`，
  服务端只会下发「靠近」或「离远」。嘴部判据（ROI 暗度、连续段计数）与阈值保留，
  回滚只需把 `MOUTH_*` 加回 `FA_ACTION_WHITELIST` 并**重建** face 容器（`restart`
  不会重读 `.env`）。移动端失败文案同步去掉「自然张嘴」「连续做两次」的措辞。
- **投屏大屏去掉入场指引**：大屏左栏原本贴「三步刷脸流程 + 学生端入口地址 + 无需扫码」，
  这些是教师口头交代入学的事，投在墙上对已在场的学生没有信息量，还挤占姓名墙宽度。
  现左栏只保留大字倒计时（「大字号剩余时间」仍是 WP-10 验收项），栏宽由 36% 收到 26%。

### Removed
- **`MOUTH_ONCE` / `MOUTH_TWICE` 从动作库撤出**：`contracts/enums/actions.json` 只声明
  2 个启用动作，`face-api.yaml` 的 `action` 枚举同步收窄；`action_hint` 传入已停用码
  返回「动作已停用」，与拼写错误的「未知动作码」区分。存量 `attendance.action_seq`
  里的历史值仍可读——`internal.yaml` 因此不对该字段设枚举，避免把已发生的合法数据
  判成非法。

### Fixed
- **发起签到只出草稿、没有确认卡**：`verify` 在一步工具跑完后直接去 `answer`，
  而发起签到是两步（`draft_session` → `create_session`），流程永远停在「应到 1 人」。
  现在一步跑完必须回 `planner` 再问一次「还有下一步吗」，收尾由 planner 写的
  `planning_done` 决定——不能用 `steps < MAX_STEPS` 判，查询路径会在 plan↔verify 空转。
- **「导出近两天考勤记录」被当成查学生**（CR-020）：规则层的姓名正则从这句里截出
  `studentName=出近两天`，而规则分支先于模型执行，模型没有纠正机会；同时「近 N 天」被
  当成本周一至今，实际只查了当天——两个问题叠在一起，表现成「明明有记录却说查无此人」。
  现在导出意图前置，姓名正则加非人名词护栏，并新增统一的相对时间解析
  （近 N 天/周、前天、昨天、上周、本周、本月，中文数字也认）。
- **「未查询到该学生」后面跟着「命中 1 条」**（CR-020）：`found=false` 被 `_count`
  计成 1 条。现在 `tool_node` 与 `answer` 两处统一按 0 计，引用行不再自相矛盾。
- **管理员点开「教师与授权」报 FA-1003**：库里账号确为 `ADMIN`，用同一账号重新登录后接口
  正常，失败的那次请求带的是旧身份令牌（界面身份来自 `/auth/me`，鉴权读令牌，两者不同步）。
  三层修正：① 鉴权改用 `AccountRoleResolver` 读库角色（见上）；② 教师管理页捕获
  `FA-1003` 时先复核 `/auth/me`，确认不是管理员就提示「当前账号（姓名/角色）没有教师管理
  权限」并跳登录页，而不是只弹一句「权限不足」；③ 监听 `storage`，别的标签页换账号后
  本页重新拉取身份并按 `route.meta.roles` 纠正路由，不再出现"右上角管理员、接口说无权限"。
- **清空教师的院系/职称不生效**：`PUT /admin/teachers/{id}` 把 `null` 当作"该字段不改"，
  而前端清空时正好发的是 `null`，于是永远改不掉。现约定 `department`/`title` 传**空串**
  表示清空（JSON 无法区分"没传"与"传 null"），前端同步改为发空串；姓名仍不允许清空
  （`@Pattern` 拦空白）。另修一层：即使传了空串，`updateById` 也会**跳过 null 字段**，
  清空必须走显式 `SET col = NULL`（`TeacherMapper.updateDepartment/updateTitle`），
  管理员编辑与教师申请审核两条路径都改用它。
- **基线帧没有人脸时的失败路径**：CR-018 后白名单只剩尺度动作，原「退回嘴部动作」的
  兜底会把学生送进一个必然失败的动作（`baseline_scale=0`）。现在直接返回 `FA-4001`，
  让学生把脸放进取景框重来，而不是跑完一轮再收「动作次数不足」。

## [1.3.1] - 2026-11-21

### Fixed
- **签到成功页的时间与身份信息**：人脸服务的 `finish` 结果此前只挑出 `attendanceId` /
  `status` 透传，契约里早已声明的 `checkinAt` 一直是 `null`，成功页只能显示 `—` 或退化成
  设备本地时间（多设备时钟不准时会误导学生）。现由 Java `commit` 返回的 `checkinAt`
  原样透传，前端优先展示服务端时间，仅在旧版人脸服务缺字段时才退回本机时间且只用于展示；
  成功页新增「姓名」行，取自登录态、不额外请求也不额外传输 PII。
  同时移除成功页的「比对相似度」行——学生不需要看阈值细节，需要时看门禁明细即可。
- **帧 seq 用例与实现对齐**：`test_out_of_order_frame_dropped` 仍按「序号跳跃即丢弃」断言，
  与 v1.3 的「容忍缺口」相悖。CI 没有模型文件会跳过该用例（22 passed / 17 skipped），
  所以一直没暴露；本机全量跑必红。改为同时钉住两个方向：`seq` 倒退（重传旧帧）丢弃、
  `seq` 跳跃（客户端丢帧）容忍并把序号对齐到当前帧。

## [1.3.0] - 2026-11-21

> 本版合并三批未发版改动：CR-013（取消二维码入口）、CR-014（建档同脸查重）、
> CR-015（签到中可申诉 + 申诉互斥）、CR-016（动作库扩为 4 个），以及真机联调
> 暴露的申诉/历史接口与基线帧编码修复。数据库迁移新增 V4。

### Added
- **动作库新增「靠近 / 离远」**（CR-016）：白名单扩为 4 个动作，新增 `MOVE_CLOSER` /
  `MOVE_AWAY`。判据是人脸**检测框尺度**（`sqrt(框宽×框高) / 画面宽`）相对基线变化
  ≥ 18% 且连续 ≥2 帧，窗口 2.5s / 10 帧，做 1 次。尺度是检测器在原图坐标下的几何量，
  不经过 `2d106det` 的形状归一化，所以"转头"不会被打成"离远"（侧脸 30° 时框宽掉 13%、
  几何平均只掉 7%）。抽样前剔除当前画面做不到的动作：脸已占满画面不抽「靠近」、
  缩小后短边 <70px 不抽「离远」；`FA_ACTION_WHITELIST` 可不重建镜像摘除新动作。
  `probe_action.py --scale` 实测静止波动 std=0.0131、分离度 13.7σ（门槛 3σ）。
- **签到过程中的申诉与互斥**（CR-015）：同一场次存在 `PENDING` 申诉时，`join` 与内部
  `commit` 均返回 `FA-5001`（文案「你已提交本次申诉」），该学生不可再参与本场签到；
  `REJECTED` / `WITHDRAWN` 之后解除互斥。`GET /api/v1/mobile/sessions/active` 新增
  `appealStatus`，移动端首页据此把场次标成「申诉中」并阻止进入采集页。采集页新增
  常驻申诉入口（顶部 + 摄像头打不开的遮罩），不再只有「刷脸失败」的结果页才能申诉。
- **建档同脸查重**（CR-014）：提交采集时把新样本与全库他人档案逐一比对，任一样本余弦
  ≥ `DUP_FACE_SIM`（默认 0.55）即整单拒绝，返回新增的 `FA-4008`（409），不写向量、
  不写同意时间戳、不改档案状态。此前同一张脸可被录进两个学号，签到时会因
  `top1 - top2 < 0.06` 的余量规则把本人判成「像两个人」（2026-11-21 现场：学号 57/158
  同脸 0.692，两次签到均 `FA-4005`）。新增 `FaceDuplicateChecker` 与 8 个纯逻辑单测。

### Security
- **人脸向量字段级加密**（CR-012）：`face_vector.embedding` 以 AES-256-GCM 落库
  （密文 = 版本 1B + IV 12B + 密文 + tag 16B）。密钥 `FA_VECTOR_AES_KEY` 经
  `deploy/.env` 注入 java 容器（32 字节 base64，未配置 = 明文落库并 WARN，仅限
  本地/CI）；存量明文行兼容读取，无需停机迁移。`InternalVectorController` 成为
  解密边界，Python 快照协议不变。新增 `VectorCrypto` 组件与 9 个纯逻辑单测。
- **备份加密**（CR-012）：`backup.sh` 在 `BACKUP_AES_KEY` 配置后对 dump 整体加密
  （AES-256-CBC + PBKDF2 600k 轮，明文不落盘）；`restore.sh` 支持 `.enc` 备份，
  口令错在 openssl 阶段直接退出不污染目标库。

### Fixed
- **申诉页「记录加载失败」**：`CaptureView.goAppeal()` 先 `clearJoin()` 再读
  `checkin.sid`，跳转成 `/appeal/null`，详情接口拿到非法路径参数，学生只看到
  「记录加载失败，请稍后重试」。改为先取 sid 再清理；`AppealView` 对无效路由参数
  就地提示「签到信息已过期，请从签到页重新进入」，不再发一次注定失败的请求。
- **签到基线帧编码**：移动端把 `canvas.toDataURL()` 的整串（含 `data:image/jpeg;base64,`
  前缀）当作 `baselineFrame` 发给 face 服务，`b64decode(validate=True)` 直接报
  `Only base64 data is allowed`，真机第一次签到即失败。前端改为剥前缀（`stripDataUrl`），
  服务端同时容忍 `data:` 前缀，新增 `tests/test_decode.py` 覆盖三种输入。
- **申诉入口打不开**：`GET /mobile/attendance/{sid}` 在没有 attendance 行时抛 `FA-2001`，
  学生刷脸失败后点「提交申诉」只看到三行「—」；且 `canAppeal` 只在 `LATE` 时为真，
  与 §4.3「`ABSENT` 可申诉」相反。现改为：无记录返回 `status=null` + 班级/课程 + 可申诉；
  `ABSENT`/`LATE`/未签成功均可申诉，已签成功/请假/免签不可；返回最近一条申诉的
  `appealId/appealStatus`，刷新后仍能看到进度与撤回入口。
- **`GET /mobile/attendance/history` 未实现**：契约与移动端「我的」页早已调用，
  服务端没有映射（404）。现已补实现（分页 + 状态过滤，关联班级/课程名与最新申诉）。

### Changed
- **取消二维码签到入口**（CR-013）：教师发起签到后，学生手机端首页直接列出本班进行中的
  场次，点击即进入刷脸签到，不再需要扫码。`POST /api/v1/mobile/sessions/{sid}/join`
  删除请求体（不再接收 `qrToken`）；`GET /api/v1/mobile/sessions/active` 改为返回数组
  （最多 20 条，带 `myStatus`）；删除 `GET /admin/sessions/{id}/qrcode`、
  `POST /admin/sessions/{id}/rotate-qr`、`QrTokenService`、`QrData`/`QrResp` schema
  与 Agent 工具 `rotate_qr`；`SESSION_QR_SECRET` 从部署配置移除。作为补偿控制，
  `join` 新增硬性前置——`face_profile.status` 必须为 `ACTIVE`，否则 `FA-4006`。
  管理端大屏由二维码改为「签到指引 + 场次信息」，去掉换码按钮与 15 秒轮询。
  `FA-3002` 保留编码但服务端不再返回；`checkin_session.qr_epoch` 列保留、不再读写。
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
