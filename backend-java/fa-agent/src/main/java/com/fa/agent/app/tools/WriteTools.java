package com.fa.agent.app.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fa.agent.api.dto.AgentDtos;
import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.app.AppealService;
import com.fa.checkin.app.SessionService;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.org.api.dto.ClassDtos;
import com.fa.org.api.dto.CourseDtos;
import com.fa.org.app.ClassService;
import com.fa.org.app.CourseService;
import com.fa.org.app.StudentService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * L2 写操作工具：每个都必须「预览 → 确认 → 执行 → 留痕 → 可撤销」。
 *
 * <p>本类只负责三件事：校验范围、构造 preview、执行（或撤销）。
 * 令牌校验、幂等、留痕由 {@code AgentToolService} 统一处理——
 * 把这套逻辑抄进 14 个工具里，迟早有一个抄漏。
 *
 * <p><b>preview 里不写库</b>：{@code plan} 方法内禁止出现任何 INSERT/UPDATE，
 * 这是两阶段确认的底线。确有需要的读取（比如算应到人数）走只读查询。
 */
@Component
public class WriteTools {

    private final SessionService sessionService;
    private final ClassService classService;
    private final CourseService courseService;
    private final StudentService studentService;
    private final AppealService appealService;
    private final QueryTools queries;
    private final DraftTools drafts;
    private final JdbcTemplate jdbc;

    public WriteTools(SessionService sessionService, ClassService classService,
                      CourseService courseService, StudentService studentService,
                      AppealService appealService, QueryTools queries, DraftTools drafts,
                      JdbcTemplate jdbc) {
        this.sessionService = sessionService;
        this.classService = classService;
        this.courseService = courseService;
        this.studentService = studentService;
        this.appealService = appealService;
        this.queries = queries;
        this.drafts = drafts;
        this.jdbc = jdbc;
    }

    public List<ToolSpec> specs() {
        return List.of(createSession(), closeSession(),
                manualCheckin(), markLeave(), markExempt(), handleAppeal(),
                createClass(), updateClass(), archiveClass(),
                createCourse(), updateCourse(), archiveCourse(),
                importStudents());
    }

    // ============================ create_session ============================

    private ToolSpec createSession() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classId", ToolSpec.int64("班级"),
                "courseId", ToolSpec.int64("课程"),
                "title", ToolSpec.str("会话标题"),
                "durationMinutes", ToolSpec.integer("时长（分钟）"),
                "lateAfterSec", ToolSpec.integer("迟到阈值（秒）")),
                List.of("classId", "courseId", "title", "durationMinutes"));
        return ToolSpec.builder("create_session", "L2", "发起签到")
                .description("为「班级 × 课程」创建一场签到会话，应到名单在创建时冻结。")
                .undoable("撤销 = 取消该会话（CANCELLED），不产生考勤")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    long courseId = p.requireLng("courseId");
                    queries.requireClass(ctx, classId);
                    queries.requireCourse(ctx, courseId);
                    Map<String, Object> clazz = queries.queryOne(
                            "SELECT name, status FROM class_group WHERE id = ?", classId);
                    Map<String, Object> course = queries.queryOne(
                            "SELECT name, status FROM course WHERE id = ?", courseId);
                    if (clazz == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
                    }
                    if (course == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
                    }
                    if (!"ACTIVE".equals(clazz.get("status")) || !"ACTIVE".equals(course.get("status"))) {
                        throw new BizException(ErrorCode.ORG_ARCHIVED, "班级或课程已归档，不能发起签到");
                    }
                    long expected = queries.expectedCount0(classId);
                    int minutes = p.integer("durationMinutes", 10);
                    int lateAfter = p.integer("lateAfterSec", 300);
                    LocalDateTime now = LocalDateTime.now();
                    List<AgentDtos.FieldDiff> diffs = List.of(
                            new AgentDtos.FieldDiff("status", "会话状态", "不存在", "ACTIVE"),
                            new AgentDtos.FieldDiff("startAt", "开始时间", "-", now.toString()),
                            new AgentDtos.FieldDiff("endAt", "结束时间", "-",
                                    now.plusMinutes(minutes).toString()),
                            new AgentDtos.FieldDiff("lateAfterSec", "迟到阈值", "-", lateAfter + " 秒"),
                            new AgentDtos.FieldDiff("expectedCount", "应到人数", "-", expected + " 人"));
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "发起一场签到",
                            clazz.get("name") + " × " + course.get("name"),
                            (int) expected,
                            expected == 0 ? "该班级没有在读学生，发起后将无人可签"
                                    : "将为 " + expected + " 名学生生成待签到名单",
                            diffs, true, null, null);
                    return new ToolSpec.Plan(preview, Map.of("none", true));
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    CheckinDtos.Session created = sessionService.create(new CheckinDtos.CreateReq(
                            p.requireLng("classId"), p.requireLng("courseId"), p.requireStr("title"),
                            p.integer("durationMinutes", 10), p.integer("lateAfterSec", 300), true));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sessionId", created.id());
                    data.put("title", created.title());
                    data.put("className", created.className());
                    data.put("courseName", created.courseName());
                    data.put("startAt", created.startAt());
                    data.put("endAt", created.endAt());
                    data.put("expectedCount", created.expectedCount());
                    return AgentDtos.ToolResult.single(data);
                })
                .undo((ctx, params, before, after) -> {
                    // sessionId 是执行后才生成的，params 里没有，只能从 after 快照取
                    long sessionId = after.path("sessionId").asLong();
                    jdbc.update("DELETE FROM attendance WHERE session_id = ?", sessionId);
                    jdbc.update("""
                            UPDATE checkin_session SET status = 'CANCELLED', closed_at = NOW(3)
                            WHERE id = ? AND status = 'ACTIVE'
                            """, sessionId);
                })
                .build();
    }

    // ============================ close_session ============================

    private ToolSpec closeSession() {
        Map<String, Object> schema = ToolSpec.object(Map.of("sessionId", ToolSpec.int64("会话")),
                List.of("sessionId"));
        return ToolSpec.builder("close_session", "L2", "结束签到")
                .description("结束会话，并把未签到者补为 ABSENT。")
                .undoable("仅 30 分钟内可撤销；撤销会把本场补的 ABSENT 一并撤回")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long sessionId = p.requireLng("sessionId");
                    Map<String, Object> s = queries.requireSession(ctx, sessionId);
                    if (!"ACTIVE".equals(s.get("status"))) {
                        throw new BizException(ErrorCode.SESSION_CLOSED, "会话不是进行中，无需结束");
                    }
                    List<Long> missing = jdbc.queryForList("""
                            SELECT e.student_id FROM session_expected e
                            WHERE e.session_id = ?
                              AND NOT EXISTS (SELECT 1 FROM attendance a
                                              WHERE a.session_id = e.session_id
                                                AND a.student_id = e.student_id)
                            """, Long.class, sessionId);
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "结束签到并补齐缺勤",
                            s.get("className") + " × " + s.get("courseName"),
                            missing.size(),
                            missing.isEmpty() ? "所有人都已有考勤记录，不会新增记录"
                                    : "将把 " + missing.size() + " 名学生标记为 ABSENT",
                            List.of(new AgentDtos.FieldDiff("status", "会话状态", "ACTIVE", "CLOSED")),
                            true, null, null);
                    return new ToolSpec.Plan(preview, Map.of("missingStudentIds", missing));
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    CheckinDtos.Session closed = sessionService.close(p.requireLng("sessionId"));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sessionId", closed.id());
                    data.put("status", closed.status());
                    data.put("presentCount", closed.presentCount());
                    data.put("absentCount", closed.absentCount());
                    return AgentDtos.ToolResult.single(data);
                })
                .undo((ctx, params, before, after) -> {
                    long sessionId = ((Number) params.get("sessionId")).longValue();
                    if (before != null && before.has("missingStudentIds")) {
                        for (JsonNode id : before.get("missingStudentIds")) {
                            jdbc.update("""
                                    DELETE FROM attendance
                                    WHERE session_id = ? AND student_id = ? AND status = 'ABSENT'
                                      AND checkin_at IS NULL
                                    """, sessionId, id.asLong());
                        }
                    }
                    jdbc.update("""
                            UPDATE checkin_session SET status = 'ACTIVE', closed_at = NULL
                            WHERE id = ? AND status = 'CLOSED'
                            """, sessionId);
                })
                .build();
    }

    // ============================ manual_checkin / mark_leave / mark_exempt ============================

    private ToolSpec manualCheckin() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "sessionId", ToolSpec.int64("会话"),
                "studentId", ToolSpec.int64("学生"),
                "reason", ToolSpec.str("补签原因"),
                "status", ToolSpec.enums("补签状态", List.of("PRESENT", "LATE"))),
                List.of("sessionId", "studentId", "reason"));
        return ToolSpec.builder("manual_checkin", "L2", "手工补签")
                .description("给指定学生手工补记为出勤或迟到，写调整原因。")
                .undoable("撤销会恢复补签前的考勤状态")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> attendancePlan(ctx, raw, schema, "PRESENT", "手工补签", "补签"))
                .exec((ctx, raw) -> attendanceExec(ctx, raw, schema, "PRESENT"))
                .undo(this::attendanceUndo)
                .build();
    }

    private ToolSpec markLeave() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "sessionId", ToolSpec.int64("会话"),
                "studentId", ToolSpec.int64("学生"),
                "reason", ToolSpec.str("请假原因")), List.of("sessionId", "studentId", "reason"));
        return ToolSpec.builder("mark_leave", "L2", "标记请假")
                .description("把某学生在一场签到里标记为请假，不计入实到。")
                .undoable("撤销会恢复标记前的考勤状态")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> attendancePlan(ctx, raw, schema, "LEAVE", "标记请假", "请假"))
                .exec((ctx, raw) -> attendanceExec(ctx, raw, schema, "LEAVE"))
                .undo(this::attendanceUndo)
                .build();
    }

    private ToolSpec markExempt() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "sessionId", ToolSpec.int64("会话"),
                "studentId", ToolSpec.int64("学生"),
                "reason", ToolSpec.str("免签原因")), List.of("sessionId", "studentId", "reason"));
        return ToolSpec.builder("mark_exempt", "L2", "标记免签")
                .description("把某学生在一场签到里标记为免签（公假等）。")
                .undoable("撤销会恢复标记前的考勤状态")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> attendancePlan(ctx, raw, schema, "EXEMPT", "标记免签", "免签"))
                .exec((ctx, raw) -> attendanceExec(ctx, raw, schema, "EXEMPT"))
                .undo(this::attendanceUndo)
                .build();
    }

    private ToolSpec.Plan attendancePlan(ToolCtx ctx, Map<String, Object> raw,
                                         Map<String, Object> schema, String targetStatus,
                                         String actionLabel, String verb) {
        Params p = Params.of(raw, schema);
        long sessionId = p.requireLng("sessionId");
        long studentId = p.requireLng("studentId");
        String reason = p.requireStr("reason");
        Map<String, Object> s = queries.requireSession(ctx, sessionId);
        Map<String, Object> stu = requireStudentInSession(ctx, sessionId, studentId);
        String before = attendanceStatus(sessionId, studentId);
        AgentDtos.Preview preview = new AgentDtos.Preview(
                actionLabel + "：" + stu.get("name"),
                s.get("className") + " × " + s.get("courseName"),
                1,
                "将把 " + stu.get("name") + "（" + stu.get("studentNo") + "）标记为 " + targetStatus,
                List.of(new AgentDtos.FieldDiff("status", "考勤状态",
                                before == null ? "无记录" : before, targetStatus),
                        new AgentDtos.FieldDiff("adjustReason", "调整原因", "-", reason)),
                true, null, null);
        return new ToolSpec.Plan(preview, Map.of(
                "sessionId", sessionId,
                "studentId", studentId,
                "beforeStatus", before == null ? "" : before));
    }

    private AgentDtos.ToolResult attendanceExec(ToolCtx ctx, Map<String, Object> raw,
                                                Map<String, Object> schema, String targetStatus) {
        Params p = Params.of(raw, schema);
        long sessionId = p.requireLng("sessionId");
        long studentId = p.requireLng("studentId");
        String reason = p.requireStr("reason");
        String status = "PRESENT".equals(targetStatus) && "LATE".equals(p.str("status"))
                ? "LATE" : targetStatus;
        jdbc.update("""
                INSERT INTO attendance (session_id, student_id, status, checkin_at,
                                        adjust_reason, adjusted_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE status = VALUES(status), adjust_reason = VALUES(adjust_reason),
                                        adjusted_by = VALUES(adjusted_by), checkin_at = VALUES(checkin_at)
                """, sessionId, studentId, status,
                "PRESENT".equals(status) || "LATE".equals(status) ? LocalDateTime.now() : null,
                reason, SecurityUtils.current().accountId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("studentId", studentId);
        data.put("status", status);
        return AgentDtos.ToolResult.single(data);
    }

    private void attendanceUndo(ToolCtx ctx, Map<String, Object> params, JsonNode before,
                                JsonNode after) {
        long sessionId = ((Number) params.get("sessionId")).longValue();
        long studentId = ((Number) params.get("studentId")).longValue();
        String beforeStatus = before == null ? "" : before.path("beforeStatus").asText("");
        if (beforeStatus.isEmpty()) {
            jdbc.update("DELETE FROM attendance WHERE session_id = ? AND student_id = ?",
                    sessionId, studentId);
        } else {
            jdbc.update("UPDATE attendance SET status = ?, adjust_reason = 'Agent 撤销调整' "
                    + "WHERE session_id = ? AND student_id = ?", beforeStatus, sessionId, studentId);
        }
    }

    // ============================ handle_appeal ============================

    private ToolSpec handleAppeal() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "appealId", ToolSpec.int64("申诉"),
                "approved", ToolSpec.bool("是否通过"),
                "reply", ToolSpec.str("回复文案")),
                List.of("appealId", "approved", "reply"));
        return ToolSpec.builder("handle_appeal", "L2", "处理申诉")
                .description("通过或驳回申诉；通过时联动把考勤改为 PRESENT。")
                .undoable("30 分钟内可撤销，撤销会把关联考勤恢复为原状态")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long appealId = p.requireLng("appealId");
                    boolean approved = Boolean.TRUE.equals(p.bool("approved", false));
                    Map<String, Object> a = queries.queryOne("""
                            SELECT ap.id, ap.status, ap.attendance_id, ap.session_id, ap.student_id,
                                   s.class_id, st.name AS student_name,
                                   COALESCE(att.status, '') AS attendance_status
                            FROM appeal ap
                            JOIN checkin_session s ON s.id = ap.session_id
                            JOIN student st ON st.id = ap.student_id
                            LEFT JOIN attendance att ON att.id = ap.attendance_id
                            WHERE ap.id = ?
                            """, appealId);
                    if (a == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "申诉不存在");
                    }
                    queries.requireClass(ctx, ((Number) a.get("classId")).longValue());
                    if (!"PENDING".equals(a.get("status"))) {
                        throw new BizException(ErrorCode.APPEAL_EXISTS, "该申诉已处理");
                    }
                    String beforeStatus = String.valueOf(a.get("attendanceStatus"));
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            (approved ? "通过" : "驳回") + "申诉：" + a.get("studentName"),
                            "申诉 #" + appealId,
                            1,
                            approved ? "通过后将把该学生本场考勤改为 PRESENT"
                                    : "驳回后考勤状态保持不变",
                            approved
                                    ? List.of(new AgentDtos.FieldDiff("status", "考勤状态",
                                            beforeStatus.isEmpty() ? "无记录" : beforeStatus, "PRESENT"))
                                    : List.of(new AgentDtos.FieldDiff("appealStatus", "申诉状态", "PENDING", "REJECTED")),
                            true, null, null);
                    return new ToolSpec.Plan(preview, Map.of(
                            "appealStatus", "PENDING",
                            "attendanceId", a.get("attendanceId") == null ? 0 : a.get("attendanceId"),
                            "attendanceStatus", beforeStatus));
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    var resp = appealService.handle(p.requireLng("appealId"),
                            new AppealService.HandleReq(Boolean.TRUE.equals(p.bool("approved", false)),
                                    p.requireStr("reply")));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("appealId", resp.id());
                    data.put("status", resp.status());
                    data.put("attendanceId", resp.attendanceId());
                    return AgentDtos.ToolResult.single(data);
                })
                .undo((ctx, params, before, after) -> {
                    long appealId = ((Number) params.get("appealId")).longValue();
                    long attendanceId = before == null ? 0 : before.path("attendanceId").asLong(0);
                    String beforeStatus = before == null ? "" : before.path("attendanceStatus").asText("");
                    jdbc.update("""
                            UPDATE appeal SET status = 'PENDING', reply = NULL, handled_by = NULL,
                                   handled_at = NULL WHERE id = ?
                            """, appealId);
                    if (attendanceId > 0 && !beforeStatus.isEmpty()) {
                        jdbc.update("UPDATE attendance SET status = ?, adjust_reason = '申诉处理已撤销' "
                                + "WHERE id = ?", beforeStatus, attendanceId);
                    }
                })
                .build();
    }

    // ============================ 班级 / 课程 CRUD ============================

    private ToolSpec createClass() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "code", ToolSpec.str("班级码，4~32 位字母数字"),
                "name", ToolSpec.str("班级名称"),
                "grade", ToolSpec.str("年级"),
                "major", ToolSpec.str("专业"),
                "ownerTeacherId", ToolSpec.int64("负责教师")),
                List.of("code", "name"));
        return ToolSpec.builder("create_class", "L2", "新建班级")
                .description("创建一个班级。班级码创建后不可修改。")
                .undoable("撤销 = 归档该班级（不产生物理删除）")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    String code = p.requireStr("code");
                    String name = p.requireStr("name");
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "新建班级",
                            code + " " + name,
                            1,
                            "将新增 1 个班级",
                            List.of(new AgentDtos.FieldDiff("code", "班级码", "-", code),
                                    new AgentDtos.FieldDiff("name", "班级名称", "-", name)),
                            true, null, null);
                    return new ToolSpec.Plan(preview, Map.of("none", true));
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    ClassDtos.Resp created = classService.create(new ClassDtos.CreateReq(
                            p.requireStr("code"), p.requireStr("name"), p.str("grade"),
                            p.str("major"), p.lng("ownerTeacherId"), null));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("classId", created.id());
                    data.put("code", created.code());
                    data.put("name", created.name());
                    return AgentDtos.ToolResult.single(data);
                })
                .undo((ctx, params, before, after) -> {
                    // 新建班级的 id 在 after 里；params 只有 code/name
                    long classId = after.path("classId").asLong();
                    jdbc.update("UPDATE class_group SET status = 'ARCHIVED' WHERE id = ?", classId);
                })
                .build();
    }

    private ToolSpec updateClass() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classId", ToolSpec.int64("班级"),
                "name", ToolSpec.str("名称"),
                "grade", ToolSpec.str("年级"),
                "major", ToolSpec.str("专业"),
                "ownerTeacherId", ToolSpec.int64("负责教师"),
                "remark", ToolSpec.str("备注")), List.of("classId"));
        return ToolSpec.builder("update_class", "L2", "编辑班级")
                .description("修改班级的名称/年级/专业/负责教师/备注。班级码不可改。")
                .undoable("撤销会恢复修改前的字段值")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    queries.requireClass(ctx, classId);
                    Map<String, Object> c = queries.queryOne("""
                            SELECT id, code, name, grade, major, owner_teacher_id, remark, status
                            FROM class_group WHERE id = ?
                            """, classId);
                    if (c == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
                    }
                    List<AgentDtos.FieldDiff> diffs = diff(c, raw, Map.of(
                            "name", "班级名称", "grade", "年级", "major", "专业",
                            "ownerTeacherId", "负责教师", "remark", "备注"));
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "编辑班级：" + c.get("name"), String.valueOf(c.get("code")),
                            diffs.isEmpty() ? 0 : 1,
                            diffs.isEmpty() ? "没有字段发生变化" : "将修改 " + diffs.size() + " 个字段",
                            diffs, true, null, null);
                    return new ToolSpec.Plan(preview, c);
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    ClassDtos.Resp updated = classService.update(classId, new ClassDtos.UpdateReq(
                            p.str("name"), p.str("grade"), p.str("major"),
                            p.lng("ownerTeacherId"), null, p.str("remark")));
                    return AgentDtos.ToolResult.single(Map.of(
                            "classId", updated.id(), "name", updated.name()));
                })
                .undo((ctx, params, before, after) -> {
                    long classId = ((Number) params.get("classId")).longValue();
                    jdbc.update("""
                            UPDATE class_group SET name = ?, grade = ?, major = ?,
                                   owner_teacher_id = ?, remark = ? WHERE id = ?
                            """, text(before, "name"), text(before, "grade"), text(before, "major"),
                            before == null || before.path("ownerTeacherId").isNull()
                                    ? null : before.path("ownerTeacherId").asLong(),
                            text(before, "remark"), classId);
                })
                .build();
    }

    private ToolSpec archiveClass() {
        Map<String, Object> schema = ToolSpec.object(Map.of("classId", ToolSpec.int64("班级")),
                List.of("classId"));
        return ToolSpec.builder("archive_class", "L2", "归档班级")
                .description("不物理删除。归档后不能再用于发起签到，历史记录仍可查。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    queries.requireClass(ctx, classId);
                    Map<String, Object> c = queries.queryOne(
                            "SELECT code, name, status FROM class_group WHERE id = ?", classId);
                    if (c == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
                    }
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "归档班级：" + c.get("name"), String.valueOf(c.get("code")), 1,
                            "归档后不能再用于发起签到，历史记录仍可查看",
                            List.of(new AgentDtos.FieldDiff("status", "状态",
                                    String.valueOf(c.get("status")), "ARCHIVED")),
                            false, null, "归档不可撤销，需要恢复请联系管理员在数据库中处理");
                    return new ToolSpec.Plan(preview, c);
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    var result = classService.delete(p.requireLng("classId"));
                    return AgentDtos.ToolResult.single(Map.of(
                            "classId", result.id(), "archived", result.archived()));
                })
                .build();
    }

    private ToolSpec createCourse() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "code", ToolSpec.str("课程编码，2~32 位大写字母数字"),
                "name", ToolSpec.str("课程名称"),
                "semester", ToolSpec.str("学期，形如 2026-2027-1"),
                "ownerTeacherId", ToolSpec.int64("开课教师")),
                List.of("code", "name"));
        return ToolSpec.builder("create_course", "L2", "新建课程")
                .description("创建一门课程。课程不含任何班级字段。")
                .undoable("撤销 = 归档该课程（不产生物理删除）")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    String code = p.requireStr("code");
                    String name = p.requireStr("name");
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "新建课程", code + " " + name, 1, "将新增 1 门课程",
                            List.of(new AgentDtos.FieldDiff("code", "课程编码", "-", code),
                                    new AgentDtos.FieldDiff("name", "课程名称", "-", name),
                                    new AgentDtos.FieldDiff("semester", "学期", "-",
                                            p.str("semester") == null ? "" : p.str("semester"))),
                            true, null, null);
                    return new ToolSpec.Plan(preview, Map.of("none", true));
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    CourseDtos.Resp created = courseService.create(new CourseDtos.CreateReq(
                            p.requireStr("code"), p.requireStr("name"), p.str("semester"),
                            p.lng("ownerTeacherId"), null));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("courseId", created.id());
                    data.put("code", created.code());
                    data.put("name", created.name());
                    return AgentDtos.ToolResult.single(data);
                })
                .undo((ctx, params, before, after) -> {
                    // 新建课程的 id 在 after 里；params 只有 code/name
                    long courseId = after.path("courseId").asLong();
                    jdbc.update("UPDATE course SET status = 'ARCHIVED' WHERE id = ?", courseId);
                })
                .build();
    }

    private ToolSpec updateCourse() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "courseId", ToolSpec.int64("课程"),
                "name", ToolSpec.str("名称"),
                "semester", ToolSpec.str("学期"),
                "ownerTeacherId", ToolSpec.int64("开课教师"),
                "remark", ToolSpec.str("备注")), List.of("courseId"));
        return ToolSpec.builder("update_course", "L2", "编辑课程")
                .description("修改课程的名称/学期/开课教师/备注。编码不可改。")
                .undoable("撤销会恢复修改前的字段值")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long courseId = p.requireLng("courseId");
                    queries.requireCourse(ctx, courseId);
                    Map<String, Object> c = queries.queryOne("""
                            SELECT id, code, name, semester, owner_teacher_id, remark
                            FROM course WHERE id = ?
                            """, courseId);
                    if (c == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
                    }
                    List<AgentDtos.FieldDiff> diffs = diff(c, raw, Map.of(
                            "name", "课程名称", "semester", "学期",
                            "ownerTeacherId", "开课教师", "remark", "备注"));
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "编辑课程：" + c.get("name"), String.valueOf(c.get("code")),
                            diffs.isEmpty() ? 0 : 1,
                            diffs.isEmpty() ? "没有字段发生变化" : "将修改 " + diffs.size() + " 个字段",
                            diffs, true, null, null);
                    return new ToolSpec.Plan(preview, c);
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long courseId = p.requireLng("courseId");
                    CourseDtos.Resp updated = courseService.update(courseId, new CourseDtos.UpdateReq(
                            p.str("name"), p.str("semester"), p.lng("ownerTeacherId"), null, p.str("remark")));
                    return AgentDtos.ToolResult.single(Map.of(
                            "courseId", updated.id(), "name", updated.name()));
                })
                .undo((ctx, params, before, after) -> {
                    long courseId = ((Number) params.get("courseId")).longValue();
                    jdbc.update("""
                            UPDATE course SET name = ?, semester = ?, owner_teacher_id = ?, remark = ?
                            WHERE id = ?
                            """, text(before, "name"), text(before, "semester"),
                            before == null || before.path("ownerTeacherId").isNull()
                                    ? null : before.path("ownerTeacherId").asLong(),
                            text(before, "remark"), courseId);
                })
                .build();
    }

    private ToolSpec archiveCourse() {
        Map<String, Object> schema = ToolSpec.object(Map.of("courseId", ToolSpec.int64("课程")),
                List.of("courseId"));
        return ToolSpec.builder("archive_course", "L2", "归档课程")
                .description("不物理删除。归档后不能再用于发起签到，历史记录仍可查。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long courseId = p.requireLng("courseId");
                    queries.requireCourse(ctx, courseId);
                    Map<String, Object> c = queries.queryOne(
                            "SELECT code, name, status FROM course WHERE id = ?", courseId);
                    if (c == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
                    }
                    AgentDtos.Preview preview = new AgentDtos.Preview(
                            "归档课程：" + c.get("name"), String.valueOf(c.get("code")), 1,
                            "归档后不能再用于发起签到，历史记录仍可查看",
                            List.of(new AgentDtos.FieldDiff("status", "状态",
                                    String.valueOf(c.get("status")), "ARCHIVED")),
                            false, null, "归档不可撤销，需要恢复请联系管理员在数据库中处理");
                    return new ToolSpec.Plan(preview, c);
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    var result = courseService.delete(p.requireLng("courseId"));
                    return AgentDtos.ToolResult.single(Map.of(
                            "courseId", result.id(), "archived", result.archived()));
                })
                .build();
    }

    // ============================ import_students ============================

    private ToolSpec importStudents() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "draftId", ToolSpec.str("来自 draft_import_students，15 分钟内有效")),
                List.of("draftId"));
        return ToolSpec.builder("import_students", "L2", "执行学生批量导入")
                .description("按 draftId 执行导入。必须先跑 draft_import_students 给用户看结果。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .plan((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    Map<String, Object> draft = drafts.loadDraft(p.requireStr("draftId"));
                    Object kind = draft.get("kind");
                    if (!"import".equals(kind)) {
                        throw new BizException(ErrorCode.NOT_FOUND, "该 draftId 不是导入草稿");
                    }
                    String classCode = String.valueOf(draft.get("classCode"));
                    String csv = String.valueOf(draft.get("csvText"));
                    Map<String, Object> clazz = queries.queryOne(
                            "SELECT id, name FROM class_group WHERE code = ?", classCode);
                    if (clazz == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "班级码无效");
                    }
                    queries.requireClass(ctx, ((Number) clazz.get("id")).longValue());
                    var preview = drafts.parseCsv(((Number) clazz.get("id")).longValue(), csv);
                    AgentDtos.Preview pv = new AgentDtos.Preview(
                            "批量导入学生",
                            String.valueOf(clazz.get("name")),
                            preview.create() + preview.update(),
                            "新增 " + preview.create() + " 人，更新 " + preview.update()
                                    + " 人，失败 " + preview.failed() + " 行",
                            List.of(new AgentDtos.FieldDiff("students", "学生数", "-",
                                    "+" + (preview.create() + preview.update()))),
                            false, null, "批量导入不可撤销，需人工回滚");
                    return new ToolSpec.Plan(pv, Map.of("draftId", p.requireStr("draftId")));
                })
                .exec((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    String draftId = p.requireStr("draftId");
                    Map<String, Object> draft = drafts.loadDraft(draftId);
                    String classCode = String.valueOf(draft.get("classCode"));
                    String csv = String.valueOf(draft.get("csvText"));
                    var result = studentService.importCsv(
                            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), classCode);
                    drafts.deleteDraft(draftId);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("total", result.total());
                    data.put("created", result.created());
                    data.put("updated", result.updated());
                    data.put("failed", result.failed());
                    return AgentDtos.ToolResult.of(data, result.total());
                })
                .build();
    }

    // ============================ 辅助 ============================

    private Map<String, Object> requireStudentInSession(ToolCtx ctx, long sessionId, long studentId) {
        Map<String, Object> stu = queries.queryOne("""
                SELECT st.id, st.student_no, st.name, st.class_id
                FROM student st WHERE st.id = ?
                """, studentId);
        if (stu == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        Integer inList = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_expected WHERE session_id = ? AND student_id = ?",
                Integer.class, sessionId, studentId);
        if (inList == null || inList == 0) {
            // 与 commit 同口径：不在应到名单里的人不能补签
            throw new BizException(ErrorCode.NOT_IN_CLASS, "该学生不在本次签到的班级中");
        }
        return stu;
    }

    private String attendanceStatus(long sessionId, long studentId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM attendance WHERE session_id = ? AND student_id = ?",
                String.class, sessionId, studentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 字段级 diff。
     *
     * <p>只对「请求里真的出现的字段」比较——没传的字段表示「不改」，
     * 若把它们也算成「before → null」，确认卡会显示一堆假的变更。
     */
    private List<AgentDtos.FieldDiff> diff(Map<String, Object> current, Map<String, Object> raw,
                                           Map<String, String> labels) {
        List<AgentDtos.FieldDiff> out = new ArrayList<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            if (!raw.containsKey(e.getKey())) {
                continue;
            }
            String key = e.getKey();
            String beforeText = current.get(key) == null ? "" : String.valueOf(current.get(key));
            String afterText = raw.get(key) == null ? "" : String.valueOf(raw.get(key));
            if (!beforeText.equals(afterText)) {
                out.add(new AgentDtos.FieldDiff(key, e.getValue(), beforeText, afterText));
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isNull() || node.path(field).isMissingNode()) {
            return null;
        }
        String v = node.path(field).asText();
        return v.isEmpty() ? null : v;
    }
}
