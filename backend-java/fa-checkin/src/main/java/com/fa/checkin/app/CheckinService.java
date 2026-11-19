package com.fa.checkin.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.domain.Attendance;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.AttendanceMapper;
import com.fa.checkin.infra.SessionMapper;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.infra.audit.AuditWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
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
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;

    /** 与 sys_config PASS_THRESHOLD 默认值一致；B4 暂用常量，接配置中心后热更新。 */
    private static final BigDecimal PASS_THRESHOLD = new BigDecimal("0.45");

    public CheckinService(SessionMapper sessionMapper, AttendanceMapper attendanceMapper,
                          JdbcTemplate jdbc, AuditWriter audit) {
        this.sessionMapper = sessionMapper;
        this.attendanceMapper = attendanceMapper;
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

    /** 学生端考勤详情（按 sessionId）。 */
    public CheckinDtos.MobileAttendance myAttendance(long sessionId, long studentId) {
        Attendance a = attendanceMapper.selectOne(Wrappers.<Attendance>lambdaQuery()
                .eq(Attendance::getSessionId, sessionId)
                .eq(Attendance::getStudentId, studentId));
        if (a == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "暂无签到记录");
        }
        CheckinSession s = sessionMapper.selectById(sessionId);
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
        return new CheckinDtos.MobileAttendance(
                a.getId(), sessionId, s.getTitle(),
                classNames.get(s.getClassId()), courseNames.get(s.getCourseId()),
                a.getStatus(), a.getCheckinAt(),
                Boolean.TRUE.equals(s.getAllowAppeal()) && "LATE".equals(a.getStatus()));
    }
}
