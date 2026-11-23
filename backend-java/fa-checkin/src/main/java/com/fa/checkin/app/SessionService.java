package com.fa.checkin.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.domain.Attendance;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.AttendanceMapper;
import com.fa.checkin.infra.SessionMapper;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.DataScope;
import com.fa.common.security.ScopeContext;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.org.app.ClassService;
import com.fa.org.app.CourseService;
import com.fa.org.infra.NameLookup;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话生命周期。
 *
 * <p>发起签到必须同时命中班级与课程的授权（§3.1）——这是最容易只判一半的地方，
 * 所以用 {@link ScopeContext#requireClassAndCourse} 一次判两个。
 *
 * <p>应到名单在创建时冻结进 {@code session_expected}：转班会改 student.class_id，
 * 现算会让历史会话的应到人数随转班漂移，等于篡改考勤记录。
 */
@Service
public class SessionService {

    private final SessionMapper sessionMapper;
    private final AttendanceMapper attendanceMapper;
    private final JdbcTemplate jdbc;
    private final NameLookup nameLookup;
    private final AuditWriter audit;
    private final ClassService classService;
    private final CourseService courseService;

    public SessionService(SessionMapper sessionMapper, AttendanceMapper attendanceMapper,
                          JdbcTemplate jdbc, NameLookup nameLookup, AuditWriter audit,
                          ClassService classService, CourseService courseService) {
        this.sessionMapper = sessionMapper;
        this.attendanceMapper = attendanceMapper;
        this.jdbc = jdbc;
        this.nameLookup = nameLookup;
        this.audit = audit;
        this.classService = classService;
        this.courseService = courseService;
    }

    @DataScope
    @Transactional
    public CheckinDtos.Session create(CheckinDtos.CreateReq req) {
        // 顺序按契约：先存在性（FA-2001），再授权（FA-2006），最后归档（FA-2004）
        ScopeContext.requireClassAndCourse(req.classId(), req.courseId());
        var clazz = classService.requireActive(req.classId());
        var course = courseService.requireActive(req.courseId());

        Long teacherId = SecurityUtils.current().teacherId();
        if (teacherId == null) {
            // checkin_session.teacher_id 是 NOT NULL，且「谁发起的」必须可追溯。
            // 管理员没有教师档案，不能顶替发起——需要签到就用教师账号。
            throw new BizException(ErrorCode.FORBIDDEN, "管理员不能发起签到，请用教师账号登录");
        }
        LocalDateTime now = LocalDateTime.now();

        CheckinSession s = new CheckinSession();
        s.setClassId(req.classId());
        s.setCourseId(req.courseId());
        s.setTeacherId(teacherId);
        s.setTitle(req.title());
        s.setStatus("ACTIVE");
        s.setStartAt(now);
        s.setEndAt(now.plusMinutes(req.durationMinutes()));
        s.setLateAfterSec(req.lateAfterSec() == null ? 300 : req.lateAfterSec());
        // qr_epoch 列保留（V1 DDL 已冻结，历史数据仍需可读），业务不再读写；
        // 新增行由 DDL 默认值 0 兜底。
        s.setAllowAppeal(req.allowAppeal() == null || req.allowAppeal());
        sessionMapper.insert(s);

        // 快照应到名单：班级在读学生 ∪ 该课程授权学生（CR-026），UNION 天然去重。
        // 班级部分与 courseId 无关（D7 主体不动）；授权部分是**追加**口径——
        // 一门课只给散生授权，整班仍走班级点名。授权只影响新会话，不追溯已存续会话。
        int expected = jdbc.update("""
                INSERT INTO session_expected (session_id, student_id, class_id)
                SELECT ?, s.id, s.class_id FROM student s
                WHERE s.class_id = ? AND s.status = 'ACTIVE'
                UNION
                SELECT ?, g.student_id, s2.class_id
                FROM course_student_grant g
                JOIN student s2 ON s2.id = g.student_id
                WHERE g.course_id = ? AND s2.status = 'ACTIVE'
                """, s.getId(), req.classId(), s.getId(), req.courseId());

        audit.write("SESSION_CREATE", "SESSION", s.getId(),
                "发起签到 " + clazz.getName() + " × " + course.getName() + "，应到 " + expected);
        return toResp(s);
    }

    @DataScope
    public PageResult<CheckinDtos.Session> list(PageQuery query, Long classId, Long courseId,
                                                String status, String dateFrom, String dateTo) {
        var allowed = ScopeContext.current().classFilter();
        if (allowed != null && allowed.isEmpty()) {
            return PageResult.empty(query);
        }
        var w = Wrappers.<CheckinSession>lambdaQuery();
        if (allowed != null) {
            w.in(CheckinSession::getClassId, allowed);
        }
        if (classId != null) {
            ScopeContext.requireClass(classId);
            w.eq(CheckinSession::getClassId, classId);
        }
        if (courseId != null) {
            w.eq(CheckinSession::getCourseId, courseId);
        }
        if (status != null && !status.isBlank()) {
            w.eq(CheckinSession::getStatus, status);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            w.ge(CheckinSession::getStartAt, LocalDateTime.parse(dateFrom + "T00:00:00"));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            w.lt(CheckinSession::getStartAt, LocalDateTime.parse(dateTo + "T00:00:00").plusDays(1));
        }
        w.orderByDesc(CheckinSession::getId);

        IPage<CheckinSession> page = sessionMapper.selectPage(new Page<>(query.page(), query.size()), w);
        List<CheckinDtos.Session> items = new ArrayList<>();
        for (CheckinSession s : page.getRecords()) {
            items.add(toResp(s));
        }
        return PageResult.of(items, page.getTotal(), query);
    }

    public CheckinSession require(long id) {
        CheckinSession s = sessionMapper.selectById(id);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return s;
    }

    /** 结束签到：未签到者补 ABSENT（幂等——重复调用不重复写）。 */
    @Transactional
    public CheckinDtos.Session close(long id) {
        CheckinSession s = require(id);
        if ("CLOSED".equals(s.getStatus())) {
            return toResp(s);           // 幂等：已关闭直接返回现状
        }
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new BizException(ErrorCode.SESSION_CLOSED, "会话已取消，不能结束");
        }
        // 只补「应到名单里且没有任何记录」的人：申诉中被改过的记录不动
        int absent = jdbc.update("""
                INSERT INTO attendance (session_id, student_id, status, created_at)
                SELECT e.session_id, e.student_id, 'ABSENT', NOW(3)
                FROM session_expected e
                WHERE e.session_id = ?
                  AND NOT EXISTS (SELECT 1 FROM attendance a
                                  WHERE a.session_id = e.session_id AND a.student_id = e.student_id)
                """, id);

        s.setStatus("CLOSED");
        s.setClosedAt(LocalDateTime.now());
        sessionMapper.updateById(s);
        audit.write("SESSION_CLOSE", "SESSION", id, "结束签到，补缺勤 " + absent);
        return toResp(s);
    }

    private CheckinDtos.Session toResp(CheckinSession s) {
        var classNames = nameLookup.classNames(List.of(s.getClassId()));
        var courseNames = nameLookup.courseNames(List.of(s.getCourseId()));
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select status, count(*) c from attendance where session_id = ? group by status",
                rs -> {
                    counts.put(rs.getString("status"), rs.getLong("c"));
                }, s.getId());
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Long expected = jdbc.queryForObject(
                "select count(*) from session_expected where session_id = ?", Long.class, s.getId());

        return new CheckinDtos.Session(
                s.getId(), s.getTitle(), s.getClassId(), classNames.get(s.getClassId()),
                s.getCourseId(), courseNames.get(s.getCourseId()),
                s.getTeacherId(),
                s.getTeacherId() == null ? null : nameLookup.teacherNames(List.of(s.getTeacherId())).get(s.getTeacherId()),
                s.getStatus(), s.getStartAt(), s.getEndAt(), s.getLateAfterSec(),
                s.getAllowAppeal(), expected == null ? 0 : expected,
                counts.getOrDefault("PRESENT", 0L),
                counts.getOrDefault("LATE", 0L),
                // ABSENT 优先用实际记录数；会话未关闭时还没有补缺勤，显示剩余人数更直观
                counts.getOrDefault("ABSENT", 0L),
                s.getClosedAt(), s.getCreatedAt());
    }

    /** 控制器取单个会话时用；不暴露实体。 */
    public CheckinDtos.Session toRespPublic(long id) {
        return toResp(require(id));
    }

    public long remainingSeconds(CheckinSession s) {
        return Math.max(0, Duration.between(LocalDateTime.now(), s.getEndAt()).toSeconds());
    }
}
