# CR-024 学生密码修改申请（申请 → 审核）

## 背景

学生忘了密码只能线下找管理员，管理员重置也没有「谁申请、谁批准」的痕迹。
CR-019（教师资料与凭据申请）已验证了「申请 → 审核」链路，本 CR 把同一条链
开放给学生侧，但**只放开改密一种申请**。

## 范围

- 学生「我的」页（`/m/me`）新增「修改密码」入口：提交申请、查看状态、撤回。
- 管理员端「学生管理」新增「密码申请」页签：红点计数、按学号/姓名检索、
  通过 / 驳回（驳回必填理由，学生端可见）。
- 不含：学号（登录名）、姓名、班级变更——学籍信息仍由管理员直改；
  教师侧不受影响（仍走 `/teacher/profile-requests`）。

## 数据库（V8）

新表 `student_profile_request`（`db/migration/V8__student_password_request.sql`）：

- 独立建表而不是扩展 `teacher_profile_request`：那张表 `teacher_id NOT NULL`
  且 `uk_tpr_pending` 生成列绑定 `teacher_id`，塞 `student_id` 语义就乱了；
  学生申请当前只有密码一项，列集也小得多。
- 没有 `old_*` 快照与资料字段：无可 diff 的资料项；将来若放开资料字段，
  照 V6 对教师表的演进方式 ALTER 加列。
- `new_password_hash` 仅审核前暂存：通过/驳回/撤回都立即清空。
- `pending_student` 生成列 + `uk_spr_pending` 唯一键：同一学生仅一条 PENDING，
  业务代码禁读写生成列。

## 后端（fa-auth）

- `StudentProfileRequestService`：submit / withdraw / listMine / list / handle。
  结构与 `TeacherProfileRequestService` 同构，差异点：
  - 申请人身份取 `CurrentUser.studentId()`（JWT `studentId`）；
  - 学生档案存活性校验：`student.status != ACTIVE` 直接 FA-2007
    （防历史数据漏停用）；
  - 审核通过：`account.password_hash = 暂存哈希`、`credential_version + 1`、
    `AccountRoleResolver.invalidate`（事务提交后生效，afterCompletion）。
- 密码规则与学生激活一致：8~64 位且同时含字母与数字；不得与当前密码相同。
- 当前密码校验失败返回 **FA-2007（409）**，刻意不用 FA-1001/401——
  前端把 401 当登录态失效处理，会把用户踢回登录页（CR-019 教训）。
- 审核用「带 `status='PENDING'` 条件的 UPDATE」抢单，防双批。
- 审计：`STUDENT_PROFILE_SUBMIT / WITHDRAW / APPROVE / REJECT`，
  target=STUDENT；密码（明文或哈希）不进审计快照。
- Controller 分两个（自助 STUDENT / 审核 ADMIN），`@PreAuthorize` 落在类上。

## 前端

- 移动端 `MeView.vue`：cell 入口（PENDING 时显示「审核中」标签）+ 底部弹层
  （当前密码 / 新密码 / 确认 / 选填说明 + 申请记录列表 + 撤回按钮）。
- 管理端 `StudentView.vue`：`el-tabs` 增加「密码申请」页签（`el-badge` 红点），
  审核对话框与教师申请审核同构。

## 契约

- `contracts/openapi/java-api.yaml`：`student-profile` tag 四个端点 +
  `StudentProfileRequestSubmitReq / StudentProfileRequest / StudentProfileRequestResp /
  StudentProfileRequestPageResp`；审核端复用 `ProfileRequestHandleReq`。
- 错误码：不新增。复用 FA-2007（申请状态冲突，含当前密码不正确）、
  FA-1003（越权）、FA-2001（申请不存在）、FA-1002（旧令牌被 cv 拦下）。
- `error-codes.json` FA-2007 note 更新为 CR-019/CR-024 并列。

## 测试

- `StudentIT.studentPasswordRequestFlow`：缺当前密码 / 当前密码错误 / 重复
  PENDING / 学生越权 / 管理员检索含班级名 / 驳回缺理由 / 撤回后再审被拒 /
  通过后旧令牌 FA-1002、旧密码 401、新密码可登录 / 审计可查。
- 断言错误码引用 `ErrorCode.PROFILE_REQUEST_CONFLICT.code()` 而不是字面量，
  避免重编号时测试悄悄失真。

## 明确不做

- 学生改学号 / 姓名 / 班级：学籍是管理员权威数据（与教师「工号不可申请」同理）。
- 邮件 / 通知：现有通知渠道不含站内信，审核结果在「我的」页申请记录中可见。
- 未激活学生：无账号，走管理员重置链路之外的激活流程。
