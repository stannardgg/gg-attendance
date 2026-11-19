package com.fa.checkin.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.checkin.domain.Appeal;
import com.fa.checkin.domain.Attendance;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.AppealMapper;
import com.fa.checkin.infra.AttendanceMapper;
import com.fa.checkin.infra.SessionMapper;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.infra.NameLookup;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 申诉流转。刻意放宽了契约的一句：
 *
 * <p>契约写「仅 ABSENT 可申诉」，但**签到失败时根本不会产生记录**（决策 A：
 * 识别不够确定直接 FA-4005，不落库），学生反而没有 attendanceId 可申诉。
 * 所以本实现允许学生对「自己应到、但没有任何记录」的会话发起申诉——
 * 否则最需要申诉的那类人恰恰申诉不了。处理时若记录不存在，审批通过则补建 PRESENT。
 *
 * <p>这条偏离会同步写进 handover 与 CR 清单，等 B5 联调时由业主确认。
 */
@Service
public class AppealService {

    private final AppealMapper appealMapper;
    private final AttendanceMapper attendanceMapper;
    private final SessionMapper sessionMapper;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;
    private final NameLookup nameLookup;

    public AppealService(AppealMapper appealMapper, AttendanceMapper attendanceMapper,
                         SessionMapper sessionMapper, JdbcTemplate jdbc, AuditWriter audit,
                         NameLookup nameLookup) {
        this.appealMapper = appealMapper;
        this.attendanceMapper = attendanceMapper;
        this.sessionMapper = sessionMapper;
        this.jdbc = jdbc;
        this.audit = audit;
        this.nameLookup = nameLookup;
    }

    // ---------------- DTO ----------------
    public record CreateReq(@NotNull Long sessionId,
                            @NotBlank @Size(min = 5, max = 500) String reason,
                            @Size(max = 255) String evidenceKey) {
    }

    public record HandleReq(@NotNull Boolean approved,
                            @NotBlank @Size(min = 2, max = 500) String reply) {
    }

    public record Resp(Long id, Long attendanceId, Long sessionId, String sessionTitle,
                       Long studentId, String studentNo, String studentName,
                       String className, String courseName, String reason, String evidenceUrl,
                       String status, String reply, LocalDateTime handledAt,
                       LocalDateTime createdAt, boolean canAppeal) {
    }

    // ---------------- 学生端 ----------------
    @Transactional
    public Resp create(long studentId, CreateReq req) {
        CheckinSession s = sessionMapper.selectById(req.sessionId());
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (!Boolean.TRUE.equals(s.getAllowAppeal())) {
            throw new BizException(ErrorCode.APPEAL_OUT_OF_SCOPE, "本次签到未开放申诉");
        }
        Long expected = jdbc.queryForObject(
                "select count(*) from session_expected where session_id = ? and student_id = ?",
                Long.class, req.sessionId(), studentId);
        if (expected == null || expected == 0) {
            throw new BizException(ErrorCode.NOT_IN_CLASS);
        }

        Attendance att = attendanceMapper.selectOne(Wrappers.<Attendance>lambdaQuery()
                .eq(Attendance::getSessionId, req.sessionId())
                .eq(Attendance::getStudentId, studentId));

        Appeal a = new Appeal();
        a.setAttendanceId(att == null ? 0L : att.getId());   // 0 = 无记录的申诉
        a.setSessionId(req.sessionId());
        a.setStudentId(studentId);
        a.setReason(req.reason());
        a.setEvidenceKey(req.evidenceKey());
        a.setStatus("PENDING");
        try {
            appealMapper.insert(a);
        } catch (DuplicateKeyException e) {
            // 生成列唯一索引：同一考勤只能有一条 PENDING
            throw new BizException(ErrorCode.APPEAL_EXISTS);
        }
        audit.write("APPEAL_CREATE", "APPEAL", a.getId(), "学生申诉 session=" + req.sessionId());
        return toResp(a);
    }

    @Transactional
    public Resp withdraw(long id, long studentId) {
        Appeal a = appealMapper.selectById(id);
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "申诉不存在");
        }
        if (a.getStudentId() != studentId) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (!"PENDING".equals(a.getStatus())) {
            throw new BizException(ErrorCode.APPEAL_EXISTS, "只能撤回待处理的申诉");
        }
        a.setStatus("WITHDRAWN");
        a.setHandledAt(LocalDateTime.now());
        appealMapper.updateById(a);
        audit.write("APPEAL_WITHDRAW", "APPEAL", id, "学生撤回申诉");
        return toResp(a);
    }

    // ---------------- 管理端 ----------------
    public PageResult<Resp> list(PageQuery query, String status, Long sessionId) {
        var w = Wrappers.<Appeal>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(Appeal::getStatus, status);
        }
        if (sessionId != null) {
            w.eq(Appeal::getSessionId, sessionId);
        }
        w.orderByDesc(Appeal::getId);
        IPage<Appeal> page = appealMapper.selectPage(new Page<>(query.page(), query.size()), w);
        List<Resp> items = new ArrayList<>();
        for (Appeal a : page.getRecords()) {
            items.add(toResp(a));
        }
        return PageResult.of(items, page.getTotal(), query);
    }

    /** 处理申诉：通过 → 同一事务把考勤改成 PRESENT（无记录则补建）。 */
    @Transactional
    @com.fa.common.audit.Audited(action = "APPEAL_HANDLE", target = "APPEAL")
    public Resp handle(long id, HandleReq req) {
        Appeal a = appealMapper.selectById(id);
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "申诉不存在");
        }
        if (!"PENDING".equals(a.getStatus())) {
            throw new BizException(ErrorCode.APPEAL_EXISTS, "该申诉已处理");
        }

        if (Boolean.TRUE.equals(req.approved())) {
            Attendance att = a.getAttendanceId() == 0L ? null
                    : attendanceMapper.selectById(a.getAttendanceId());
            if (att == null) {
                att = new Attendance();
                att.setSessionId(a.getSessionId());
                att.setStudentId(a.getStudentId());
                att.setStatus("PRESENT");
                att.setCheckinAt(LocalDateTime.now());
                att.setAdjustReason("申诉通过（原无记录）");
                att.setAdjustedBy(SecurityUtils.current().accountId());
                try {
                    attendanceMapper.insert(att);
                } catch (DuplicateKeyException e) {
                    // 处理期间学生恰好自己签到了：改那条即可
                    att = attendanceMapper.selectOne(Wrappers.<Attendance>lambdaQuery()
                            .eq(Attendance::getSessionId, a.getSessionId())
                            .eq(Attendance::getStudentId, a.getStudentId()));
                    att.setStatus("PRESENT");
                    att.setAdjustReason("申诉通过（覆盖原记录）");
                    att.setAdjustedBy(SecurityUtils.current().accountId());
                    attendanceMapper.updateById(att);
                }
            } else {
                att.setStatus("PRESENT");
                att.setAdjustReason("申诉通过");
                att.setAdjustedBy(SecurityUtils.current().accountId());
                attendanceMapper.updateById(att);
            }
            a.setStatus("APPROVED");
        } else {
            a.setStatus("REJECTED");
        }
        a.setReply(req.reply());
        a.setHandledBy(SecurityUtils.current().accountId());
        a.setHandledAt(LocalDateTime.now());
        appealMapper.updateById(a);
        // 审计由 @Audited 切面统一写，这里不再重复
        return toResp(a);
    }

    private Resp toResp(Appeal a) {
        CheckinSession s = sessionMapper.selectById(a.getSessionId());
        var classNames = s == null ? Map.<Long, String>of()
                : nameLookup.classNames(List.of(s.getClassId()));
        var courseNames = s == null ? Map.<Long, String>of()
                : nameLookup.courseNames(List.of(s.getCourseId()));
        Map<String, Object> stu = jdbc.queryForMap(
                "select student_no, name from student where id = ?", a.getStudentId());
        return new Resp(a.getId(), a.getAttendanceId(), a.getSessionId(),
                s == null ? null : s.getTitle(), a.getStudentId(),
                String.valueOf(stu.get("student_no")), String.valueOf(stu.get("name")),
                s == null ? null : classNames.get(s.getClassId()),
                s == null ? null : courseNames.get(s.getCourseId()),
                a.getReason(), a.getEvidenceKey() == null ? null : "/files/" + a.getEvidenceKey(),
                a.getStatus(), a.getReply(), a.getHandledAt(), a.getCreatedAt(),
                Boolean.TRUE.equals(s.getAllowAppeal()) && "PENDING".equals(a.getStatus()));
    }
}
