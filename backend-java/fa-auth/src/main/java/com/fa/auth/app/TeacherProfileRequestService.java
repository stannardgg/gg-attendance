package com.fa.auth.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.auth.domain.Account;
import com.fa.auth.domain.Teacher;
import com.fa.auth.domain.TeacherProfileRequest;
import com.fa.auth.infra.AccountMapper;
import com.fa.auth.infra.TeacherMapper;
import com.fa.auth.infra.TeacherProfileRequestMapper;
import com.fa.auth.security.AccountRoleResolver;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 教师账号与资料修改申请（CR-019，CR-022 扩展）。
 *
 * <p>为什么不让教师直接改 teacher/account：姓名与登录名会出现在考勤导出、
 * 审计与证书上，密码则是账号本身；改动必须留「谁申请、谁批准」。所以本服务
 * 只有两条写路径：教师提交申请，管理员审核；真正落库只发生在 {@link #handle}
 * 的通过分支。
 *
 * <p>五个边界：
 * <ul>
 *   <li>可申请字段：name / department / title / 登录用户名 / 密码。
 *       登录名是 account.username 与 teacher.teacher_no 双写，审核通过时同一
 *       事务改两处；</li>
 *   <li>改登录名或改密码必须先验当前密码 —— 有人在教室里开着教师电脑时，
 *       不该能顺手把账号换掉；</li>
 *   <li>密码只存 BCrypt 哈希且只暂存：明文不进库、不进日志、不进审计，
 *       通过/驳回/撤回后立即清空暂存列；</li>
 *   <li>同一教师同时只能有一条 PENDING（uk_tpr_pending 生成列兜底）；</li>
 *   <li>院系/职称传空串表示"清空该字段"，传 null 表示"本次不申请"。</li>
 * </ul>
 */
@Service
public class TeacherProfileRequestService {

    private static final Set<String> STATUSES =
            Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN");

    /** 与创建教师时的密码规则保持一致：8~64 位且同时含字母与数字。 */
    private static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).+$";

    private final TeacherProfileRequestMapper requestMapper;
    private final TeacherMapper teacherMapper;
    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountRoleResolver roleResolver;
    private final AuditWriter audit;

    public TeacherProfileRequestService(TeacherProfileRequestMapper requestMapper,
                                        TeacherMapper teacherMapper,
                                        AccountMapper accountMapper,
                                        PasswordEncoder passwordEncoder,
                                        AccountRoleResolver roleResolver,
                                        AuditWriter audit) {
        this.requestMapper = requestMapper;
        this.teacherMapper = teacherMapper;
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.roleResolver = roleResolver;
        this.audit = audit;
    }

    /* ---------------- DTO ---------------- */

    /**
     * 未填的字段传 null；院系/职称传空串表示清空。
     *
     * <p>密码相关字段只在"确实要改"时才传：{@code newPassword} 非空即视为
     * 申请改密，此时 {@code currentPassword} 必填。
     */
    public record SubmitReq(
            @Size(max = 64) String name,
            @Size(max = 120) String department,
            @Size(max = 64) String title,
            @Size(max = 32)
            @Pattern(regexp = "^\\S+$", message = "登录用户名不能包含空格")
            String username,
            @Size(max = 64) String currentPassword,
            @Size(min = 8, max = 64)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码须同时包含字母与数字")
            String newPassword,
            @Size(max = 500) String reason) {
    }

    public record HandleReq(@NotNull Boolean approved, @Size(max = 500) String note) {
    }

    public record Resp(
            Long id,
            Long teacherId,
            /** 教师当前工号（不是申请值） */
            String teacherNo,
            /** 教师当前姓名（不是申请值），便于审核页对照 */
            String teacherName,
            String oldUsername,
            String oldName,
            String oldDepartment,
            String oldTitle,
            String newUsername,
            String newName,
            String newDepartment,
            String newTitle,
            /** 是否申请了重置密码；只给标记，哈希永不出现在响应里 */
            boolean passwordChange,
            String reason,
            String status,
            String reviewNote,
            String reviewerUsername,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt) {
    }

    /* ---------------- 教师侧 ---------------- */

    @Transactional
    public Resp submit(SubmitReq req) {
        CurrentUser user = SecurityUtils.current();
        if (user.teacherId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有教师账号可以提交修改申请");
        }
        Teacher teacher = teacherMapper.selectById(user.teacherId());
        if (teacher == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师档案不存在");
        }
        Account account = accountMapper.selectById(teacher.getAccountId());
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师账号不存在");
        }

        String newName = normalizeName(req.name());
        String newDepartment = normalizeOptional(req.department());
        String newTitle = normalizeOptional(req.title());
        String newUsername = normalizeUsername(req.username());
        String newPassword = normalizeNewPassword(req.newPassword());

        boolean wantUsername = differs(newUsername, teacher.getTeacherNo());
        boolean wantPassword = newPassword != null;

        boolean changed = wantUsername || wantPassword
                || differs(newName, teacher.getName())
                || differs(newDepartment, teacher.getDepartment())
                || differs(newTitle, teacher.getTitle());
        if (!changed) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                    "提交内容与当前资料一致，无需申请");
        }

        // 改账号凭据要先证明自己是本人：否则坐在别人电脑前就能把登录名换掉
        if (wantUsername || wantPassword) {
            requireCurrentPassword(req.currentPassword(), account);
            if (wantPassword && passwordEncoder.matches(newPassword, account.getPasswordHash())) {
                throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                        "新密码不能与当前密码相同");
            }
        }
        if (wantUsername) {
            assertUsernameFree(newUsername, teacher, account, false);
        }

        TeacherProfileRequest row = new TeacherProfileRequest();
        row.setTeacherId(teacher.getId());
        row.setAccountId(user.accountId());
        row.setOldUsername(teacher.getTeacherNo());
        row.setOldName(teacher.getName());
        row.setOldDepartment(teacher.getDepartment());
        row.setOldTitle(teacher.getTitle());
        row.setNewUsername(wantUsername ? newUsername : null);
        row.setNewName(newName);
        row.setNewDepartment(newDepartment);
        row.setNewTitle(newTitle);
        row.setPasswordChange(wantPassword);
        // 提交时就哈希：明文只活在这一个请求的内存里
        row.setNewPasswordHash(wantPassword ? passwordEncoder.encode(newPassword) : null);
        row.setReason(trimToNull(req.reason()));
        row.setStatus("PENDING");
        try {
            requestMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // uk_tpr_pending：同一教师同时只允许一条在办申请
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                    "你已有一条待审核的申请，请等待处理或先撤回");
        }

        audit.write("TEACHER_PROFILE_SUBMIT", "TEACHER", teacher.getId(),
                "教师提交修改申请 #" + row.getId() + describeKinds(wantUsername, wantPassword));
        // 重新读一次：created_at 由数据库默认值生成，不回读会返回 null
        return toRespList(List.of(requestMapper.selectById(row.getId()))).get(0);
    }

    /** 教师撤回自己的待审申请。暂存的密码哈希一并清掉。 */
    @Transactional
    public Resp withdraw(long id) {
        CurrentUser user = SecurityUtils.current();
        TeacherProfileRequest row = requestMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!Objects.equals(row.getAccountId(), user.accountId())) {
            // 不暴露"存在但不属于你"：与不存在同一句话
            throw new BizException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!"PENDING".equals(row.getStatus())) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，无法撤回");
        }
        int claimed = requestMapper.update(null, Wrappers.<TeacherProfileRequest>lambdaUpdate()
                .eq(TeacherProfileRequest::getId, id)
                .eq(TeacherProfileRequest::getStatus, "PENDING")
                .set(TeacherProfileRequest::getStatus, "WITHDRAWN")
                .set(TeacherProfileRequest::getNewPasswordHash, null));
        if (claimed == 0) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，无法撤回");
        }
        audit.write("TEACHER_PROFILE_WITHDRAW", "TEACHER", row.getTeacherId(),
                "教师撤回修改申请 #" + id);
        return toRespList(List.of(requestMapper.selectById(id))).get(0);
    }

    /** 教师查看自己的申请记录（含历史）。 */
    public PageResult<Resp> listMine(PageQuery query) {
        CurrentUser user = SecurityUtils.current();
        if (user.teacherId() == null) {
            return PageResult.empty(query);
        }
        IPage<TeacherProfileRequest> page = requestMapper.selectPage(
                new Page<>(query.page(), query.size()),
                Wrappers.<TeacherProfileRequest>lambdaQuery()
                        .eq(TeacherProfileRequest::getTeacherId, user.teacherId())
                        .orderByDesc(TeacherProfileRequest::getId));
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    /* ---------------- 管理员侧 ---------------- */

    /**
     * @param status  空 / ALL 表示全部；非法值按全部处理（列表筛选不该 400）
     * @param keyword 教师姓名或工号
     */
    public PageResult<Resp> list(PageQuery query, String status, String keyword) {
        requireAdmin();

        var wrapper = Wrappers.<TeacherProfileRequest>lambdaQuery();
        if (status != null && STATUSES.contains(status)) {
            wrapper.eq(TeacherProfileRequest::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            List<Long> teacherIds = teacherMapper.selectList(
                            Wrappers.<Teacher>lambdaQuery()
                                    .select(Teacher::getId)
                                    .and(w -> w.like(Teacher::getName, keyword.trim())
                                            .or().like(Teacher::getTeacherNo, keyword.trim())))
                    .stream().map(Teacher::getId).toList();
            if (teacherIds.isEmpty()) {
                return PageResult.empty(query);
            }
            wrapper.in(TeacherProfileRequest::getTeacherId, teacherIds);
        }
        wrapper.orderByDesc(TeacherProfileRequest::getId);

        IPage<TeacherProfileRequest> page = requestMapper.selectPage(
                new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    /**
     * 审核。通过时把申请值落到 teacher / account，并把实时改前/改后写进
     * audit_log；驳回必须写理由，教师端要能看到"为什么不行"。
     *
     * <p>用「带 status='PENDING' 条件的更新」先抢单：两个管理员同时点通过时，
     * 后一个的 claim 拿到 0 行，直接 FA-2007，不会把同一份申请应用两遍。
     */
    @Transactional
    public Resp handle(long id, HandleReq req) {
        requireAdmin();
        TeacherProfileRequest row = requestMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!"PENDING".equals(row.getStatus())) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，请刷新后重试");
        }
        Teacher teacher = teacherMapper.selectById(row.getTeacherId());
        if (teacher == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师不存在");
        }
        Account account = accountMapper.selectById(teacher.getAccountId());
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "教师账号不存在");
        }

        boolean approved = Boolean.TRUE.equals(req.approved());
        String note = trimToNull(req.note());
        if (!approved && note == null) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "驳回时必须填写理由");
        }

        int claimed = requestMapper.update(null, Wrappers.<TeacherProfileRequest>lambdaUpdate()
                .eq(TeacherProfileRequest::getId, id)
                .eq(TeacherProfileRequest::getStatus, "PENDING")
                .set(TeacherProfileRequest::getStatus, approved ? "APPROVED" : "REJECTED")
                .set(TeacherProfileRequest::getReviewerId, SecurityUtils.currentAccountId())
                .set(TeacherProfileRequest::getReviewNote, note)
                .set(TeacherProfileRequest::getReviewedAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，请刷新后重试");
        }

        if (approved) {
            applyApproved(row, teacher, account);
        } else {
            audit.write("TEACHER_PROFILE_REJECT", "TEACHER", teacher.getId(),
                    "驳回修改申请 #" + id + "：" + note);
        }
        // 无论通过还是驳回，暂存的密码哈希都不该留在业务表里
        clearPasswordHash(id);

        return toRespList(List.of(requestMapper.selectById(id))).get(0);
    }

    /* ---------------- 内部 ---------------- */

    private void applyApproved(TeacherProfileRequest row, Teacher teacher, Account account) {
        Map<String, Object> before = snapshot(teacher);

        if (row.getNewUsername() != null) {
            // 提交到审批之间可能被别人占用：此时宁可让管理员驳回，也不能改成重名
            assertUsernameFree(row.getNewUsername(), teacher, account, true);
            teacher.setTeacherNo(row.getNewUsername());
            account.setUsername(row.getNewUsername());
        }
        if (row.getNewName() != null) {
            teacher.setName(row.getNewName());
        }
        // 空串是「清空」：实体里置 null 后 updateById 会跳过该列，
        // 所以清空必须单独走一条显式 SET（见 TeacherMapper.updateDepartment）。
        boolean clearDepartment = false;
        boolean clearTitle = false;
        if (row.getNewDepartment() != null) {
            String v = blankToNull(row.getNewDepartment());
            teacher.setDepartment(v);
            clearDepartment = v == null;
        }
        if (row.getNewTitle() != null) {
            String v = blankToNull(row.getNewTitle());
            teacher.setTitle(v);
            clearTitle = v == null;
        }
        teacherMapper.updateById(teacher);
        if (clearDepartment) {
            teacherMapper.updateDepartment(teacher.getId(), null);
        }
        if (clearTitle) {
            teacherMapper.updateTitle(teacher.getId(), null);
        }

        boolean credentialChanged = false;
        if (Boolean.TRUE.equals(row.getPasswordChange()) && row.getNewPasswordHash() != null) {
            account.setPasswordHash(row.getNewPasswordHash());
            credentialChanged = true;
        }
        if (row.getNewUsername() != null) {
            credentialChanged = true;
        }
        if (credentialChanged) {
            // cv+1：该账号此前签发的全部令牌立即失效（不依赖 Redis）
            int version = account.getCredentialVersion() == null ? 0 : account.getCredentialVersion();
            account.setCredentialVersion(version + 1);
            accountMapper.updateById(account);
            roleResolver.invalidate(account.getId());
        }

        String detail = "通过修改申请 #" + row.getId()
                + describeKinds(row.getNewUsername() != null,
                        Boolean.TRUE.equals(row.getPasswordChange()));
        audit.write("TEACHER_PROFILE_APPROVE", "TEACHER", teacher.getId(),
                before, snapshot(teacher), detail);
    }

    private void clearPasswordHash(long id) {
        requestMapper.update(null, Wrappers.<TeacherProfileRequest>lambdaUpdate()
                .eq(TeacherProfileRequest::getId, id)
                .set(TeacherProfileRequest::getNewPasswordHash, null));
    }

    private void requireCurrentPassword(String raw, Account account) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                    "修改登录用户名或密码需要先输入当前密码");
        }
        if (!passwordEncoder.matches(raw, account.getPasswordHash())) {
            // 刻意不用 FA-1001（401）：那会让前端把用户踢回登录页，
            // 而这里只是一次表单校验失败
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "当前密码不正确");
        }
    }

    /** 登录名双写：teacher.teacher_no 与 account.username 都不能撞（排除自己）。 */
    private void assertUsernameFree(String username, Teacher teacher, Account account, boolean forReview) {
        String message = forReview
                // 审核场景要告诉管理员下一步做什么，而不是让他"换一个"
                ? "登录用户名 " + username + " 已被占用，无法通过；请驳回本申请并让教师换一个"
                : "登录用户名 " + username + " 已被占用，请换一个";
        Long dupTeacher = teacherMapper.selectCount(Wrappers.<Teacher>lambdaQuery()
                .eq(Teacher::getTeacherNo, username)
                .ne(Teacher::getId, teacher.getId()));
        if (dupTeacher != null && dupTeacher > 0) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, message);
        }
        Long dupAccount = accountMapper.selectCount(Wrappers.<Account>lambdaQuery()
                .eq(Account::getUsername, username)
                .ne(Account::getId, account.getId()));
        if (dupAccount != null && dupAccount > 0) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, message);
        }
    }

    private void requireAdmin() {
        if (!SecurityUtils.current().isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /** 审计快照：只放身份字段。密码（明文或哈希）一律不进审计。 */
    private Map<String, Object> snapshot(Teacher t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("teacherNo", t.getTeacherNo());
        m.put("name", t.getName());
        m.put("department", t.getDepartment());
        m.put("title", t.getTitle());
        return m;
    }

    private String describeKinds(boolean username, boolean password) {
        StringBuilder sb = new StringBuilder();
        if (username) {
            sb.append("、登录用户名");
        }
        if (password) {
            sb.append("、登录密码（旧会话已失效）");
        }
        return sb.isEmpty() ? "" : "（含" + sb.substring(1) + "）";
    }

    /** 只回填教师与审核人信息，避免 N+1。 */
    private List<Resp> toRespList(List<TeacherProfileRequest> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> teacherIds = rows.stream().map(TeacherProfileRequest::getTeacherId).distinct().toList();
        Map<Long, Teacher> teachers = new HashMap<>();
        teacherMapper.selectBatchIds(teacherIds).forEach(t -> teachers.put(t.getId(), t));

        List<Long> reviewerIds = rows.stream().map(TeacherProfileRequest::getReviewerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> reviewers = new HashMap<>();
        if (!reviewerIds.isEmpty()) {
            accountMapper.selectBatchIds(reviewerIds).forEach(a -> reviewers.put(a.getId(), a.getUsername()));
        }

        return rows.stream().map(r -> {
            Teacher t = teachers.get(r.getTeacherId());
            return new Resp(r.getId(), r.getTeacherId(),
                    t == null ? null : t.getTeacherNo(),
                    t == null ? null : t.getName(),
                    r.getOldUsername(), r.getOldName(), r.getOldDepartment(), r.getOldTitle(),
                    r.getNewUsername(), r.getNewName(), r.getNewDepartment(), r.getNewTitle(),
                    Boolean.TRUE.equals(r.getPasswordChange()),
                    r.getReason(), r.getStatus(), r.getReviewNote(),
                    r.getReviewerId() == null ? null : reviewers.get(r.getReviewerId()),
                    r.getReviewedAt(), r.getCreatedAt());
        }).toList();
    }

    /** 姓名不允许留空：传了就必须是非空串，否则视为未申请（与院系/职称的清空语义不同）。 */
    private String normalizeName(String value) {
        return trimToNull(value);
    }

    /** 院系/职称：null=未申请，""=清空，其余=新值。 */
    private String normalizeOptional(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeUsername(String value) {
        return trimToNull(value);
    }

    /** 新密码：空白视为未申请；给了就按与创建账号相同的规则校验。 */
    private String normalizeNewPassword(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return null;
        }
        if (v.length() < 8 || v.length() > 64) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "新密码长度需为 8~64 位");
        }
        if (!v.matches(PASSWORD_RULE)) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "新密码须同时包含字母与数字");
        }
        return v;
    }

    private boolean differs(String requested, String current) {
        if (requested == null) {
            return false;
        }
        return !Objects.equals(blankToNull(requested), blankToNull(current));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
