package com.fa.checkin.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.domain.Appeal;
import com.fa.checkin.domain.Attendance;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.AppealMapper;
import com.fa.checkin.infra.AttendanceMapper;
import com.fa.checkin.infra.SessionMapper;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.infra.audit.AuditWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内部签到接口的业务实现：上下文查询 + 结果提交。
 *
 * <p>提交的三条判定，顺序即防御深度：
 *  1. 会话必须 ACTIVE；
 *  2. 学生必须在应到快照里（异班学生 → FA-3004）；
 *  3. 相似度必须 ≥ PASS_THRESHOLD —— 即使调用方是自家的 Python 也复核一次。
 *     0.38~0.45 的「不够确定」区间按既定决策直接失败（FA-4005），
 *     不落模棱两可的记录，让学生走申诉。考勤表里只允许存在四种确定状态。
 *
 * <p>幂等：uk_att_session_student 冲突时捕获 DuplicateKeyException 返回 FA-3003，
 * 不是 500。并发两次 commit 只有一次成功。
 */
@Service
public class CheckinService {

    private final SessionMapper sessionMapper;
    private final AttendanceMapper attendanceMapper;
    private final AppealMapper appealMapper;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;

    /** 与 sys_config PASS_THRESHOLD 默认值一致；B4 暂用常量，接配置中心后热更新。 */
    private static final BigDecimal PASS_THRESHOLD = new BigDecimal("0.45");

    public CheckinService(SessionMapper sessionMapper, AttendanceMapper attendanceMapper,
                          AppealMapper appealMapper, JdbcTemplate jdbc, AuditWriter audit) {
        this.sessionMapper = sessionMapper;
        this.attendanceMapper = attendanceMapper;
        this.appealMapper = appealMapper;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public CheckinDtos.ContextResp context(long sessionId, long studentId) {
        CheckinSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        Integer inClass = jdbc.queryForObject("""
                SELECT count(*) FROM student WHERE id = ? AND class_id = ? AND status = 'ACTIVE'
                """, Integer.class, studentId, s.getClassId());
        String studentStatus = jdbc.query("""
                SELECT status FROM student WHERE id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, studentId);
        Integer faceActive = jdbc.queryForObject("""
                SELECT count(*) FROM face_profile WHERE student_id = ? AND status = 'ACTIVE'
                """, Integer.class, studentId);

        return new CheckinDtos.ContextResp(
                s.getId(), s.getStatus(), s.getClassId(), s.getCourseId(),
                s.getStartAt(), s.getEndAt(),
                inClass != null && inClass > 0,
                studentStatus,
                faceActive != null && faceActive > 0,
                Boolean.TRUE.equals(s.getAllowAppeal()));
    }

    @Transactional
    public CheckinDtos.CommitData commit(CheckinDtos.CommitReq req) {
        CheckinSession s = sessionMapper.selectById(req.sessionId());
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new BizException(ErrorCode.SESSION_CLOSED, "签到已结束");
        }

        Long inExpected = jdbc.queryForObject(
                "select count(*) from session_expected where session_id = ? and student_id = ?",
                Long.class, req.sessionId(), req.studentId());
        if (inExpected == null || inExpected == 0) {
            throw new BizException(ErrorCode.NOT_IN_CLASS, "你不在本次签到的应到名单中");
        }

        // 已提交申诉的场次不允许再签到：申诉是「我没签上，请老师人工核对」的意思表示，
        // 提交后继续刷脸会让「申诉通过补建 PRESENT」和真实记录互相打架，也会让老师白核对。
        // join 已经拦过一次，这里是第二道（学生可能先领了票、再在另一个标签页申诉）。
        if (hasPendingAppeal(req.sessionId(), req.studentId())) {
            throw new BizException(ErrorCode.APPEAL_EXISTS, "你已提交本次申诉");
        }

        // 防御性复核：识别不够确定时不允许落库（0.38~0.45 区间按决策 A 直接失败）
        if (req.similarity() == null || req.similarity().compareTo(PASS_THRESHOLD) < 0) {
            throw new BizException(ErrorCode.FACE_MISMATCH,
                    "识别不够确定，请重试；多次失败可发起申诉");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lateLine = s.getStartAt().plusSeconds(s.getLateAfterSec());
        String status = now.isAfter(lateLine) ? "LATE" : "PRESENT";

        Attendance a = new Attendance();
        a.setSessionId(req.sessionId());
        a.setStudentId(req.studentId());
        a.setStatus(status);
        a.setCheckinAt(now);
        a.setSimilarity(req.similarity());
        a.setLivenessMedian(req.livenessMedian());
        a.setIdentitySim(req.identitySim());
        a.setActionCount(req.actionCount());
        a.setActionSeq(req.actionSeq());
        a.setDeviceHash(req.deviceHash());
        a.setClientIp(req.clientIp());
        a.setSnapshotRef(req.snapshotRef());

        try {
            attendanceMapper.insert(a);
        } catch (DuplicateKeyException e) {
            // 幂等键冲突 = 同一会话同一学生已有记录：这是并发重复提交，不是系统故障
            throw new BizException(ErrorCode.ALREADY_CHECKED_IN, "你已完成签到");
        }

        audit.write("CHECKIN_COMMIT", "ATTENDANCE", a.getId(),
                "签到成功 " + status + " sim=" + req.similarity());
        String studentName = jdbc.query(
                "SELECT name FROM student WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, req.studentId());
        return new CheckinDtos.CommitData(a.getId(), status, now, false, studentName);
    }

    /**
     * 学生端考勤详情（按 sessionId）。
     *
     * <p>三种输入都要能答：
     * 1. 已有记录 → 按记录答；
     * 2. 会话存在但本人还没签成功 → status=null，仍给班级/课程，并允许申诉
     *    （AppealService 建申诉时 attendanceId 落 0，不依赖 attendance 行）；
     * 3. 会话都不存在 → 才是 FA-2001。
     */
    public CheckinDtos.MobileAttendance myAttendance(long sessionId, long studentId) {
        CheckinSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        Attendance a = attendanceMapper.selectOne(Wrappers.<Attendance>lambdaQuery()
                .eq(Attendance::getSessionId, sessionId)
                .eq(Attendance::getStudentId, studentId));
        // ★ 这里必须是 ResultSetExtractor（一个参数），不能是 RowMapper（两个参数）。
        // 写成 (rs, i) -> rs.next() ? ... 会被推断成 RowMapper，此时游标已经在唯一那一行上，
        // rs.next() 直接返回 false → 返回空 Map → 学生端看到 className/courseName 全是 null。
        // 用 jdbc.query 的单参数 lambda 才是 ResultSetExtractor，语义无歧义。
        Map<Long, String> classNames = jdbc.query(
                "select name from class_group where id = ?",
                rs -> rs.next() ? Map.of(s.getClassId(), rs.getString(1)) : Map.of(),
                s.getClassId());
        Map<Long, String> courseNames = jdbc.query(
                "select name from course where id = ?",
                rs -> rs.next() ? Map.of(s.getCourseId(), rs.getString(1)) : Map.of(),
                s.getCourseId());
        Appeal latest = latestAppeal(sessionId, studentId);
        String status = a == null ? null : a.getStatus();
        return new CheckinDtos.MobileAttendance(
                a == null ? 0L : a.getId(), sessionId, s.getTitle(),
                classNames.get(s.getClassId()), courseNames.get(s.getCourseId()),
                status, a == null ? null : a.getCheckinAt(),
                canAppeal(s, status, latest),
                latest == null ? null : latest.getId(),
                latest == null ? null : latest.getStatus());
    }

    /**
     * 申诉资格：缺勤与迟到可以申诉（§4.3），会话进行中尚未签成功的同样可以
     * ——「刷脸一直失败」正是失败申诉要兜的场景。
     * 已签成功/请假/免签无需申诉；已有一条在办（PENDING/APPROVED）的申诉时不重复放行。
     */
    private boolean canAppeal(CheckinSession s, String status, Appeal latest) {
        if (!Boolean.TRUE.equals(s.getAllowAppeal())) {
            return false;
        }
        boolean eligible = status == null || "ABSENT".equals(status) || "LATE".equals(status);
        if (!eligible) {
            return false;
        }
        return latest == null
                || "REJECTED".equals(latest.getStatus())
                || "WITHDRAWN".equals(latest.getStatus());
    }

    private Appeal latestAppeal(long sessionId, long studentId) {
        return appealMapper.selectOne(Wrappers.<Appeal>lambdaQuery()
                .eq(Appeal::getSessionId, sessionId)
                .eq(Appeal::getStudentId, studentId)
                .orderByDesc(Appeal::getId)
                .last("limit 1"));
    }

    /**
     * 是否存在待处理的申诉。
     *
     * <p>只拦 PENDING：撤回（WITHDRAWN）或驳回（REJECTED）之后学生仍应能自己签到 ——
     * 「申诉被驳回」的语义就是「请你按正常流程签」，此时再拦等于把人锁死。
     */
    private boolean hasPendingAppeal(long sessionId, long studentId) {
        Long n = jdbc.queryForObject(
                "select count(*) from appeal where session_id = ? and student_id = ? "
                        + "and status = 'PENDING'",
                Long.class, sessionId, studentId);
        return n != null && n > 0;
    }

    /**
     * 学生端签到历史（契约 /api/v1/mobile/attendance/history）。
     * 只查 attendance 行：没有记录的场次不该出现在「我的记录」里。
     */
    public CheckinDtos.HistoryPage history(long studentId, int page, int size, String status) {
        int p = Math.max(1, page);
        int sz = Math.min(100, Math.max(1, size));
        boolean filter = status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status);
        String where = " where a.student_id = ?" + (filter ? " and a.status = ?" : "");
        Object[] countArgs = filter ? new Object[]{studentId, status} : new Object[]{studentId};
        Long total = jdbc.queryForObject("select count(*) from attendance a" + where,
                Long.class, countArgs);
        List<Object> args = new ArrayList<>(Arrays.asList(countArgs));
        args.add(sz);
        args.add((p - 1) * sz);
        List<CheckinDtos.MobileAttendance> items = jdbc.query("""
                select a.id, a.session_id, a.status, a.checkin_at,
                       s.title, s.allow_appeal,
                       c.name as class_name, co.name as course_name,
                       ap.id as appeal_id, ap.status as appeal_status
                  from attendance a
                  join checkin_session s on s.id = a.session_id
                  left join class_group c on c.id = s.class_id
                  left join course co on co.id = s.course_id
                  left join appeal ap on ap.id = (
                       select max(x.id) from appeal x
                        where x.session_id = a.session_id and x.student_id = a.student_id)
                """ + where + " order by a.id desc limit ? offset ?",
                (rs, i) -> {
                    String st = rs.getString("status");
                    boolean allow = rs.getBoolean("allow_appeal");
                    String apStatus = rs.getString("appeal_status");
                    boolean appealable = allow
                            && ("ABSENT".equals(st) || "LATE".equals(st))
                            && (apStatus == null || "REJECTED".equals(apStatus) || "WITHDRAWN".equals(apStatus));
                    Long apId = rs.getObject("appeal_id") == null ? null : rs.getLong("appeal_id");
                    return new CheckinDtos.MobileAttendance(
                            rs.getLong("id"), rs.getLong("session_id"), rs.getString("title"),
                            rs.getString("class_name"), rs.getString("course_name"),
                            st, rs.getTimestamp("checkin_at") == null
                                    ? null : rs.getTimestamp("checkin_at").toLocalDateTime(),
                            appealable, apId, apStatus);
                },
                args.toArray());
        return new CheckinDtos.HistoryPage(items, total == null ? 0 : total, p, sz);
    }
}
