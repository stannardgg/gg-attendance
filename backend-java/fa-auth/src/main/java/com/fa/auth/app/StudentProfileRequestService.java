package com.fa.auth.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.auth.domain.Account;
import com.fa.auth.domain.StudentProfileRequest;
import com.fa.auth.infra.AccountMapper;
import com.fa.auth.infra.StudentProfileRequestMapper;
import com.fa.auth.security.AccountRoleResolver;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.domain.ClassGroup;
import com.fa.org.domain.Student;
import com.fa.org.infra.ClassGroupMapper;
import com.fa.org.infra.StudentMapper;
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
 * 学生密码修改申请（CR-024）。
 *
 * <p>学生忘了密码只能线下找管理员，改动也没有「谁申请、谁批准」的痕迹。本服务
 * 把这条链变成：学生提交申请 → 管理员审核 → 通过才写 account.password_hash，
 * 并 credential_version+1 使该账号已签发的全部令牌立即失效。真正落库只发生在
 * {@link #handle} 的通过分支。
 *
 * <p>四个边界：
 * <ul>
 *   <li>只放开改密一种申请：学号是登录名且由管理员维护，学生不能申请改学号、
 *       姓名、班级（资料类错误找管理员改）；</li>
 *   <li>必须验当前密码——手机没锁屏被别人拿着时，不该能顺手把账号换掉；</li>
 *   <li>密码只存 BCrypt 哈希且只暂存：明文不进库、不进日志、不进审计，
 *       通过/驳回/撤回后立即清空暂存列；</li>
 *   <li>同一学生同时只能有一条 PENDING（uk_spr_pending 生成列兜底）。</li>
 * </ul>
 */
@Service
public class StudentProfileRequestService {

    private static final Set<String> STATUSES =
            Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN");

    /** 与学生激活时的密码规则保持一致：8~64 位且同时含字母与数字。 */
    private static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).+$";

    private final StudentProfileRequestMapper requestMapper;
    private final StudentMapper studentMapper;
    private final ClassGroupMapper classGroupMapper;
    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccountRoleResolver roleResolver;
    private final AuditWriter audit;

    public StudentProfileRequestService(StudentProfileRequestMapper requestMapper,
                                        StudentMapper studentMapper,
                                        ClassGroupMapper classGroupMapper,
                                        AccountMapper accountMapper,
                                        PasswordEncoder passwordEncoder,
                                        AccountRoleResolver roleResolver,
                                        AuditWriter audit) {
        this.requestMapper = requestMapper;
        this.studentMapper = studentMapper;
        this.classGroupMapper = classGroupMapper;
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.roleResolver = roleResolver;
        this.audit = audit;
    }

    /* ---------------- DTO ---------------- */

    public record SubmitReq(
            @Size(max = 64) String currentPassword,
            @Size(min = 8, max = 64)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "新密码须同时包含字母与数字")
            String newPassword,
            @Size(max = 500) String reason) {
    }

    public record HandleReq(Boolean approved, @Size(max = 500) String note) {
    }

    public record Resp(
            Long id,
            Long studentId,
            /** 学生当前学号（登录名，不是申请值——学生不能申请改学号） */
            String studentNo,
            /** 学生当前姓名（不是申请值），便于审核页对照 */
            String studentName,
            /** 学生当前班级名，便于管理员辨认 */
            String className,
            /** 学生申请了重置密码；恒为 true，字段保留以与教师申请响应同构 */
            boolean passwordChange,
            String reason,
            String status,
            String reviewNote,
            String reviewerUsername,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt) {
    }

    /* ---------------- 学生侧 ---------------- */

    @Transactional
    public Resp submit(SubmitReq req) {
        CurrentUser user = SecurityUtils.current();
        if (user.studentId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有学生账号可以提交修改申请");
        }
        Student student = studentMapper.selectById(user.studentId());
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生档案不存在");
        }
        if (!"ACTIVE".equals(student.getStatus())) {
            // 已转出/归档的学生账号本就不该还能登录；多一道闸防止历史数据漏停用
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "学籍状态异常，无法提交申请");
        }
        Account account = accountMapper.selectById(student.getAccountId());
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生账号不存在");
        }

        String newPassword = normalizeNewPassword(req.newPassword());
        requireCurrentPassword(req.currentPassword(), account);
        if (passwordEncoder.matches(newPassword, account.getPasswordHash())) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "新密码不能与当前密码相同");
        }

        StudentProfileRequest row = new StudentProfileRequest();
        row.setStudentId(student.getId());
        row.setAccountId(user.accountId());
        // 提交时就哈希：明文只活在这一个请求的内存里
        row.setNewPasswordHash(passwordEncoder.encode(newPassword));
        row.setReason(trimToNull(req.reason()));
        row.setStatus("PENDING");
        try {
            requestMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // uk_spr_pending：同一学生同时只允许一条在办申请
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                    "你已有一条待审核的申请，请等待处理或先撤回");
        }

        audit.write("STUDENT_PROFILE_SUBMIT", "STUDENT", student.getId(),
                "学生提交密码修改申请 #" + row.getId());
        // 重新读一次：created_at 由数据库默认值生成，不回读会返回 null
        return toRespList(List.of(requestMapper.selectById(row.getId()))).get(0);
    }

    /** 学生撤回自己的待审申请。暂存的密码哈希一并清掉。 */
    @Transactional
    public Resp withdraw(long id) {
        CurrentUser user = SecurityUtils.current();
        StudentProfileRequest row = requestMapper.selectById(id);
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
        int claimed = requestMapper.update(null, Wrappers.<StudentProfileRequest>lambdaUpdate()
                .eq(StudentProfileRequest::getId, id)
                .eq(StudentProfileRequest::getStatus, "PENDING")
                .set(StudentProfileRequest::getStatus, "WITHDRAWN")
                .set(StudentProfileRequest::getNewPasswordHash, null));
        if (claimed == 0) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，无法撤回");
        }
        audit.write("STUDENT_PROFILE_WITHDRAW", "STUDENT", row.getStudentId(),
                "学生撤回密码修改申请 #" + id);
        return toRespList(List.of(requestMapper.selectById(id))).get(0);
    }

    /** 学生查看自己的申请记录（含历史）。 */
    public PageResult<Resp> listMine(PageQuery query) {
        CurrentUser user = SecurityUtils.current();
        if (user.studentId() == null) {
            return PageResult.empty(query);
        }
        IPage<StudentProfileRequest> page = requestMapper.selectPage(
                new Page<>(query.page(), query.size()),
                Wrappers.<StudentProfileRequest>lambdaQuery()
                        .eq(StudentProfileRequest::getStudentId, user.studentId())
                        .orderByDesc(StudentProfileRequest::getId));
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    /* ---------------- 管理员侧 ---------------- */

    /**
     * @param status  空 / ALL 表示全部；非法值按全部处理（列表筛选不该 400）
     * @param keyword 学生姓名或学号
     */
    public PageResult<Resp> list(PageQuery query, String status, String keyword) {
        requireAdmin();

        var wrapper = Wrappers.<StudentProfileRequest>lambdaQuery();
        if (status != null && STATUSES.contains(status)) {
            wrapper.eq(StudentProfileRequest::getStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            List<Long> studentIds = studentMapper.selectList(
                            Wrappers.<Student>lambdaQuery()
                                    .select(Student::getId)
                                    .and(w -> w.like(Student::getName, keyword.trim())
                                            .or().like(Student::getStudentNo, keyword.trim())))
                    .stream().map(Student::getId).toList();
            if (studentIds.isEmpty()) {
                return PageResult.empty(query);
            }
            wrapper.in(StudentProfileRequest::getStudentId, studentIds);
        }
        wrapper.orderByDesc(StudentProfileRequest::getId);

        IPage<StudentProfileRequest> page = requestMapper.selectPage(
                new Page<>(query.page(), query.size()), wrapper);
        return PageResult.of(toRespList(page.getRecords()), page.getTotal(), query);
    }

    /**
     * 审核。通过时把暂存哈希写入 account 并 cv+1（旧令牌立即失效），实时状态
     * 写进 audit_log；驳回必须写理由，学生端要能看到"为什么不行"。
     *
     * <p>用「带 status='PENDING' 条件的更新」先抢单：两个管理员同时点通过时，
     * 后一个的 claim 拿到 0 行，直接 FA-2007，不会把同一份申请应用两遍。
     */
    @Transactional
    public Resp handle(long id, HandleReq req) {
        requireAdmin();
        StudentProfileRequest row = requestMapper.selectById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (!"PENDING".equals(row.getStatus())) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，请刷新后重试");
        }
        Student student = studentMapper.selectById(row.getStudentId());
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        Account account = accountMapper.selectById(student.getAccountId());
        if (account == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生账号不存在");
        }

        boolean approved = Boolean.TRUE.equals(req.approved());
        String note = trimToNull(req.note());
        if (!approved && note == null) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "驳回时必须填写理由");
        }

        int claimed = requestMapper.update(null, Wrappers.<StudentProfileRequest>lambdaUpdate()
                .eq(StudentProfileRequest::getId, id)
                .eq(StudentProfileRequest::getStatus, "PENDING")
                .set(StudentProfileRequest::getStatus, approved ? "APPROVED" : "REJECTED")
                .set(StudentProfileRequest::getReviewerId, SecurityUtils.currentAccountId())
                .set(StudentProfileRequest::getReviewNote, note)
                .set(StudentProfileRequest::getReviewedAt, LocalDateTime.now()));
        if (claimed == 0) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "该申请已被处理，请刷新后重试");
        }

        if (approved) {
            applyApproved(row, student, account);
        } else {
            audit.write("STUDENT_PROFILE_REJECT", "STUDENT", student.getId(),
                    "驳回密码修改申请 #" + id + "：" + note);
        }
        // 无论通过还是驳回，暂存的密码哈希都不该留在业务表里
        clearPasswordHash(id);

        return toRespList(List.of(requestMapper.selectById(id))).get(0);
    }

    /* ---------------- 内部 ---------------- */

    private void applyApproved(StudentProfileRequest row, Student student, Account account) {
        Map<String, Object> before = snapshot(student);

        // 暂存哈希在抢单后清空前使用；若已被并发清空（理论上不会发生），宁可失败也不写空值
        if (row.getNewPasswordHash() == null || row.getNewPasswordHash().isBlank()) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                    "该申请暂存的新密码已失效，请让学生重新提交");
        }
        account.setPasswordHash(row.getNewPasswordHash());
        // cv+1：该账号此前签发的全部令牌立即失效（不依赖 Redis）
        int version = account.getCredentialVersion() == null ? 0 : account.getCredentialVersion();
        account.setCredentialVersion(version + 1);
        accountMapper.updateById(account);
        // 角色缓存必须等事务提交后再删（invalidate 内部已注册 afterCompletion）
        roleResolver.invalidate(account.getId());

        audit.write("STUDENT_PROFILE_APPROVE", "STUDENT", student.getId(),
                before, snapshot(student), "通过密码修改申请 #" + row.getId() + "（登录密码已重置，旧会话已失效）");
    }

    private void clearPasswordHash(long id) {
        requestMapper.update(null, Wrappers.<StudentProfileRequest>lambdaUpdate()
                .eq(StudentProfileRequest::getId, id)
                .set(StudentProfileRequest::getNewPasswordHash, null));
    }

    private void requireCurrentPassword(String raw, Account account) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT,
                    "修改密码需要先输入当前密码");
        }
        if (!passwordEncoder.matches(raw, account.getPasswordHash())) {
            // 刻意不用 FA-1001（401）：那会让前端把用户踢回登录页，
            // 而这里只是一次表单校验失败
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "当前密码不正确");
        }
    }

    private void requireAdmin() {
        if (!SecurityUtils.current().isAdmin()) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /** 审计快照：只放身份字段。密码（明文或哈希）一律不进审计。 */
    private Map<String, Object> snapshot(Student s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("studentNo", s.getStudentNo());
        m.put("name", s.getName());
        m.put("classId", s.getClassId());
        return m;
    }

    /** 只回填学生、班级与审核人信息，避免 N+1。 */
    private List<Resp> toRespList(List<StudentProfileRequest> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> studentIds = rows.stream().map(StudentProfileRequest::getStudentId).distinct().toList();
        Map<Long, Student> students = new HashMap<>();
        studentMapper.selectBatchIds(studentIds).forEach(s -> students.put(s.getId(), s));

        List<Long> classIds = students.values().stream().map(Student::getClassId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> classNames = new HashMap<>();
        if (!classIds.isEmpty()) {
            classGroupMapper.selectBatchIds(classIds).forEach(c -> classNames.put(c.getId(), c.getName()));
        }

        List<Long> reviewerIds = rows.stream().map(StudentProfileRequest::getReviewerId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, String> reviewers = new HashMap<>();
        if (!reviewerIds.isEmpty()) {
            accountMapper.selectBatchIds(reviewerIds).forEach(a -> reviewers.put(a.getId(), a.getUsername()));
        }

        return rows.stream().map(r -> {
            Student s = students.get(r.getStudentId());
            return new Resp(r.getId(), r.getStudentId(),
                    s == null ? null : s.getStudentNo(),
                    s == null ? null : s.getName(),
                    s == null ? null : classNames.get(s.getClassId()),
                    true,
                    r.getReason(), r.getStatus(), r.getReviewNote(),
                    r.getReviewerId() == null ? null : reviewers.get(r.getReviewerId()),
                    r.getReviewedAt(), r.getCreatedAt());
        }).toList();
    }

    /** 新密码：给了就按与学生激活相同的规则校验。 */
    private String normalizeNewPassword(String value) {
        String v = value == null ? null : value.trim();
        if (v == null || v.isEmpty()) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "新密码不能为空");
        }
        if (v.length() < 8 || v.length() > 64) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "新密码长度需为 8~64 位");
        }
        if (!v.matches(PASSWORD_RULE)) {
            throw new BizException(ErrorCode.PROFILE_REQUEST_CONFLICT, "新密码须同时包含字母与数字");
        }
        return v;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
