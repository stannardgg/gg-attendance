package com.fa.checkin.api;

import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.app.CheckinService;
import com.fa.checkin.app.CheckinTicketService;
import com.fa.checkin.app.QrTokenService;
import com.fa.checkin.app.SessionService;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.AttendanceMapper;
import com.fa.checkin.infra.SessionMapper;
import com.fa.common.api.Result;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.org.infra.NameLookup;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端签到入口。
 *
 * <p>join 的职责是「发一张 60 秒的票据」，不是「把人放进签到」——
 * 真正的准入判定在 Python 的三道门禁里。这里只做三件便宜的事：
 * 会话是否在有效期内、你是不是这次应到的人、你有没有已经签过。
 */
@RestController
@RequestMapping("/api/v1/mobile")
@PreAuthorize("hasRole('STUDENT')")
public class MobileCheckinController {

    private final SessionService sessionService;
    private final SessionMapper sessionMapper;
    private final AttendanceMapper attendanceMapper;
    private final CheckinTicketService tickets;
    private final QrTokenService qr;
    private final CheckinService checkinService;
    private final NameLookup nameLookup;
    private final JdbcTemplate jdbc;

    public MobileCheckinController(SessionService sessionService, SessionMapper sessionMapper,
                                   AttendanceMapper attendanceMapper, CheckinTicketService tickets,
                                   QrTokenService qr, CheckinService checkinService,
                                   NameLookup nameLookup, JdbcTemplate jdbc) {
        this.sessionService = sessionService;
        this.sessionMapper = sessionMapper;
        this.attendanceMapper = attendanceMapper;
        this.tickets = tickets;
        this.qr = qr;
        this.checkinService = checkinService;
        this.nameLookup = nameLookup;
        this.jdbc = jdbc;
    }

    /** 本班当前进行中的会话（最多一条，没有则 data=null）。 */
    @GetMapping("/sessions/active")
    public Result<CheckinDtos.ActiveSession> active() {
        var user = SecurityUtils.current();
        Long classId = jdbc.queryForObject(
                "select class_id from student where id = ?", Long.class, user.studentId());
        if (classId == null) {
            return Result.ok(null);
        }
        List<CheckinSession> list = sessionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<CheckinSession>lambdaQuery()
                        .eq(CheckinSession::getClassId, classId)
                        .eq(CheckinSession::getStatus, "ACTIVE")
                        .orderByDesc(CheckinSession::getId)
                        .last("limit 1"));
        if (list.isEmpty()) {
            return Result.ok(null);
        }
        CheckinSession s = list.get(0);
        var cls = nameLookup.classNames(List.of(s.getClassId()));
        var crs = nameLookup.courseNames(List.of(s.getCourseId()));
        String myStatus = "NOT_STARTED";
        var att = attendanceMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.fa.checkin.domain.Attendance>lambdaQuery()
                        .eq(com.fa.checkin.domain.Attendance::getSessionId, s.getId())
                        .eq(com.fa.checkin.domain.Attendance::getStudentId, user.studentId()));
        if (att != null) {
            myStatus = att.getStatus();
        }
        return Result.ok(new CheckinDtos.ActiveSession(
                s.getId(), s.getTitle(), cls.get(s.getClassId()), crs.get(s.getCourseId()),
                s.getStartAt(), s.getEndAt(), sessionService.remainingSeconds(s), myStatus));
    }

    @PostMapping("/sessions/{sid}/join")
    public Result<CheckinDtos.JoinData> join(@PathVariable long sid,
                                             @Valid @RequestBody CheckinDtos.JoinReq req) {
        var user = SecurityUtils.current();
        CheckinSession s = sessionService.require(sid);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new BizException(ErrorCode.SESSION_CLOSED);
        }
        // 二维码校验（契约 §/mobile/sessions/{sid}/join）：这是「人在现场」的唯一凭证。
        // 顺序放在状态之后：已经结束的会话告诉学生"结束了"比"码无效"更有用。
        // 允许当前与上一个 15s 窗口，换码（qrEpoch+1）后旧码立刻失效。
        if (!qr.verify(sid, s.getQrEpoch(), req.qrToken())) {
            throw new BizException(ErrorCode.QR_INVALID);
        }
        Long expected = jdbc.queryForObject(
                "select count(*) from session_expected where session_id = ? and student_id = ?",
                Long.class, sid, user.studentId());
        if (expected == null || expected == 0) {
            throw new BizException(ErrorCode.NOT_IN_CLASS);
        }
        Long done = jdbc.queryForObject(
                "select count(*) from attendance where session_id = ? and student_id = ?",
                Long.class, sid, user.studentId());
        if (done != null && done > 0) {
            throw new BizException(ErrorCode.ALREADY_CHECKED_IN);
        }
        Long faceActive = jdbc.queryForObject(
                "select count(*) from face_profile where student_id = ? and status = 'ACTIVE'",
                Long.class, user.studentId());

        var cls = nameLookup.classNames(List.of(s.getClassId()));
        var crs = nameLookup.courseNames(List.of(s.getCourseId()));
        boolean challengeRequired = faceActive != null && faceActive > 0;
        return Result.ok(new CheckinDtos.JoinData(
                tickets.issue(sid, user.studentId()), tickets.ttl(),
                cls.get(s.getClassId()), crs.get(s.getCourseId()), s.getTitle(),
                sessionService.remainingSeconds(s), challengeRequired, 3));
    }

    @GetMapping("/attendance/{sid}")
    public Result<CheckinDtos.MobileAttendance> attendance(@PathVariable long sid) {
        return Result.ok(checkinService.myAttendance(sid, SecurityUtils.current().studentId()));
    }
}
