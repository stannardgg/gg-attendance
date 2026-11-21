package com.fa.checkin.api;

import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.app.CheckinService;
import com.fa.checkin.app.CheckinTicketService;
import com.fa.checkin.app.SessionService;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.AttendanceMapper;
import com.fa.checkin.infra.SessionMapper;
import com.fa.common.api.Result;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.org.infra.NameLookup;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移动端签到入口。
 *
 * <p>入口不再依赖二维码：学生登录后直接看到本班进行中的签到，点进来就能刷脸。
 * 「人在现场」的凭证只剩人脸的三道门禁（活体 / 首末帧一致性 / 动作），
 * 因此 join 在这里必须硬性要求人脸档案 ACTIVE —— 缺了它，任何知道 sessionId
 * 的人都能换到票据，等于把门禁绕过去。
 *
 * <p>join 的职责仍只是「发一张 60 秒的票据」，不做准入判定：会话是否在有效期内、
 * 你是不是这次应到的人、你有没有已经签过、你有没有已经提交待处理的申诉、
 * 你的脸能不能用。
 */
@RestController
@RequestMapping("/api/v1/mobile")
@PreAuthorize("hasRole('STUDENT')")
public class MobileCheckinController {

    private final SessionService sessionService;
    private final SessionMapper sessionMapper;
    private final AttendanceMapper attendanceMapper;
    private final CheckinTicketService tickets;
    private final CheckinService checkinService;
    private final NameLookup nameLookup;
    private final JdbcTemplate jdbc;

    public MobileCheckinController(SessionService sessionService, SessionMapper sessionMapper,
                                   AttendanceMapper attendanceMapper, CheckinTicketService tickets,
                                   CheckinService checkinService, NameLookup nameLookup,
                                   JdbcTemplate jdbc) {
        this.sessionService = sessionService;
        this.sessionMapper = sessionMapper;
        this.attendanceMapper = attendanceMapper;
        this.tickets = tickets;
        this.checkinService = checkinService;
        this.nameLookup = nameLookup;
        this.jdbc = jdbc;
    }

    /**
     * 学生当前能签的场次（按 id 倒序，最多 20 条）。
     *
     * <p>列表以 **`session_expected` 快照**为基表，不是按 `student.class_id` 现算。
     * 两个理由：
     * 1. 与 `join` 的判据保持一致——首页列出来的场次，点进去一定换得到票；
     *    否则插班生会看到一场"看得到、签不了"的签到。
     * 2. 转班之后，历史场次的应到名单不该跟着变（CR-001）。
     */
    @GetMapping("/sessions/active")
    public Result<List<CheckinDtos.ActiveSession>> active() {
        var user = SecurityUtils.current();
        List<Long> mySessionIds = jdbc.queryForList(
                "select session_id from session_expected where student_id = ? "
                        + "order by session_id desc limit 50",
                Long.class, user.studentId());
        if (mySessionIds.isEmpty()) {
            return Result.ok(List.of());
        }
        List<CheckinSession> list = sessionMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<CheckinSession>lambdaQuery()
                        .in(CheckinSession::getId, mySessionIds)
                        .eq(CheckinSession::getStatus, "ACTIVE")
                        // 只列真正还在时间窗内的：过期但教师忘了结束的场次不该继续挂在首页
                        .gt(CheckinSession::getEndAt, LocalDateTime.now())
                        .orderByDesc(CheckinSession::getId)
                        .last("limit 20"));
        if (list.isEmpty()) {
            return Result.ok(List.of());
        }

        Map<Long, String> myStatus = new HashMap<>();
        String placeholders = String.join(",", list.stream().map(s -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(user.studentId());
        list.forEach(s -> args.add(s.getId()));
        // RowCallbackHandler：游标已在当前行，逐行读取即可
        jdbc.query("select session_id, status from attendance where student_id = ? "
                        + "and session_id in (" + placeholders + ")",
                rs -> {
                    myStatus.put(rs.getLong("session_id"), rs.getString("status"));
                },
                args.toArray());

        // 本人每场最新一条申诉的状态（会话级，不是考勤级）。
        // 首页据此把「待签到」改成「申诉中」并禁止再进采集页 —— 只靠 join 报错的话，
        // 学生看到的是「待签到」标签点进去才被拒，等于把结果藏在一次多余点击后面。
        Map<Long, String> myAppeal = new HashMap<>();
        List<Object> appealArgs = new ArrayList<>();
        appealArgs.add(user.studentId());
        list.forEach(s -> appealArgs.add(s.getId()));
        jdbc.query("select a.session_id, a.status from appeal a "
                        + "join (select max(id) as id from appeal where student_id = ? "
                        + "and session_id in (" + placeholders + ") group by session_id) x on x.id = a.id",
                rs -> {
                    myAppeal.put(rs.getLong("session_id"), rs.getString("status"));
                },
                appealArgs.toArray());

        var cls = nameLookup.classNames(list.stream().map(CheckinSession::getClassId).distinct().toList());
        var crs = nameLookup.courseNames(list.stream().map(CheckinSession::getCourseId).distinct().toList());
        List<CheckinDtos.ActiveSession> resp = list.stream()
                .map(s -> new CheckinDtos.ActiveSession(
                        s.getId(), s.getTitle(), cls.get(s.getClassId()), crs.get(s.getCourseId()),
                        s.getStartAt(), s.getEndAt(), sessionService.remainingSeconds(s),
                        myStatus.getOrDefault(s.getId(), "NOT_STARTED"),
                        myAppeal.get(s.getId())))
                .toList();
        return Result.ok(resp);
    }

    /**
     * 进入签到并领取票据。
     *
     * <p>无请求体：学生身份来自 JWT，会话来自路径参数。五道便宜的前置检查
     * 全部保留，只是把「二维码是否有效」换成了「人脸档案是否可用」。
     * 其中「已提交待处理申诉」与「已签到」同级互斥：两者都意味着这场不需要再刷脸。
     */
    @PostMapping("/sessions/{sid}/join")
    public Result<CheckinDtos.JoinData> join(@PathVariable long sid) {
        var user = SecurityUtils.current();
        CheckinSession s = sessionService.require(sid);
        if (!"ACTIVE".equals(s.getStatus()) || !s.getEndAt().isAfter(LocalDateTime.now())) {
            // 已关闭与已过时间窗对学生是同一件事：这场签到结束了
            throw new BizException(ErrorCode.SESSION_CLOSED);
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
        // 当前会话已经提交待处理申诉：不允许再领票进采集页。
        // 申诉与签到互斥 —— 否则学生一边等老师核对、一边自己签成功，
        // 老师手上那条 PENDING 就失去了处理对象。
        Long pendingAppeal = jdbc.queryForObject(
                "select count(*) from appeal where session_id = ? and student_id = ? "
                        + "and status = 'PENDING'",
                Long.class, sid, user.studentId());
        if (pendingAppeal != null && pendingAppeal > 0) {
            throw new BizException(ErrorCode.APPEAL_EXISTS, "你已提交本次申诉");
        }
        Long faceActive = jdbc.queryForObject(
                "select count(*) from face_profile where student_id = ? and status = 'ACTIVE'",
                Long.class, user.studentId());
        if (faceActive == null || faceActive == 0) {
            // 只保留刷脸通道：没有人脸档案就没有任何可用的签到方式
            throw new BizException(ErrorCode.FACE_NOT_ACTIVE);
        }

        var cls = nameLookup.classNames(List.of(s.getClassId()));
        var crs = nameLookup.courseNames(List.of(s.getCourseId()));
        return Result.ok(new CheckinDtos.JoinData(
                tickets.issue(sid, user.studentId()), tickets.ttl(),
                cls.get(s.getClassId()), crs.get(s.getCourseId()), s.getTitle(),
                sessionService.remainingSeconds(s), true, 3));
    }

    /**
     * 本人签到历史（契约 operationId: mobileAttendanceHistory）。
     * 必须写在 {@code /attendance/{sid}} 之前吗？不需要 —— Spring 的 PatternsRequestCondition
     * 优先匹配字面量路径，`/attendance/history` 不会被 `{sid}` 抢走。
     */
    @GetMapping("/attendance/history")
    public Result<CheckinDtos.HistoryPage> history(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return Result.ok(checkinService.history(
                SecurityUtils.current().studentId(), page, size, status));
    }

    @GetMapping("/attendance/{sid}")
    public Result<CheckinDtos.MobileAttendance> attendance(@PathVariable long sid) {
        return Result.ok(checkinService.myAttendance(sid, SecurityUtils.current().studentId()));
    }
}
