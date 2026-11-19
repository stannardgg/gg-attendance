package com.fa.agent.app.tools;

import com.fa.agent.api.dto.AgentDtos;
import com.fa.agent.app.AgentPrincipal;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * L0 只读工具（§11.4 的九张表）。
 *
 * <p>三条贯穿全文件的约定：
 * <ol>
 *   <li><b>列名 → camelCase</b> 由 {@link #query} 统一完成，SQL 里可以照抄库里的列名，
 *       不用担心「查询写对了但字段名对不上契约」。</li>
 *   <li><b>数据范围先于查询</b>：显式传入未授权的 classId/courseId 直接 FA-6004，
 *       列表类查询则把范围拼成 IN 条件。<b>不换参数重试</b>是 Agent 的纪律，
 *       服务端这里只负责如实拒绝。</li>
 *   <li><b>不返回隐私字段</b>：所有 SELECT 都显式列名，没有 {@code SELECT *}，
 *       手机号/身份证/向量在原表里就不存在于这些查询的投影中（§13.4）。</li>
 * </ol>
 */
@Component
public class QueryTools {

    private final JdbcTemplate jdbc;

    public QueryTools(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ToolSpec> specs() {
        return List.of(
                getAttendanceStatus(),
                getSessionSummary(),
                listSessions(),
                getStudentProfile(),
                getClassRoster(),
                getCourseStats(),
                getClassStats(),
                getAttendanceTrend(),
                listPendingAppeals());
    }

    // ============================ 1. get_attendance_status ============================

    private ToolSpec getAttendanceStatus() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "studentNo", ToolSpec.str("学号"),
                "studentName", ToolSpec.str("姓名，可精确匹配，也可模糊"),
                "classId", ToolSpec.int64("限定班级"),
                "courseId", ToolSpec.int64("限定课程"),
                "dateFrom", ToolSpec.date("起始日期"),
                "dateTo", ToolSpec.date("结束日期")), List.of());
        return ToolSpec.builder("get_attendance_status", "L0", "查询学生考勤状态")
                .description("按学号或姓名查某人在指定时间范围内的每次签到结果。姓名命中多人时返回候选，必须反问。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    String studentNo = p.str("studentNo");
                    String studentName = p.str("studentName");
                    if ((studentNo == null || studentNo.isEmpty()) && (studentName == null || studentName.isEmpty())) {
                        throw new BizException(ErrorCode.NOT_FOUND, "必须提供 studentNo 或 studentName");
                    }
                    Long classId = p.lng("classId");
                    Long courseId = p.lng("courseId");
                    if (classId != null) {
                        requireClass(ctx, classId);
                    }
                    if (courseId != null) {
                        requireCourse(ctx, courseId);
                    }

                    // 先定位学生：这一步决定「是否命中多人」，也决定后面要不要反问
                    List<Map<String, Object>> students = studentNo != null && !studentNo.isEmpty()
                            ? query("""
                            SELECT st.id, st.student_no, st.name, cg.name AS class_name
                            FROM student st LEFT JOIN class_group cg ON cg.id = st.class_id
                            WHERE st.student_no = ?
                            """, studentNo)
                            : query("""
                            SELECT st.id, st.student_no, st.name, cg.name AS class_name
                            FROM student st LEFT JOIN class_group cg ON cg.id = st.class_id
                            WHERE st.name = ? OR st.name LIKE ?
                            """, studentName, "%" + studentName + "%");

                    if (students.isEmpty()) {
                        return AgentDtos.ToolResult.of(Map.of(
                                "found", false,
                                "message", "未查询到该学生"), 0);
                    }
                    if (students.size() > 1) {
                        List<Map<String, Object>> candidates = students.stream()
                                .map(s -> Map.of(
                                        "studentId", s.get("id"),
                                        "studentNo", s.get("studentNo"),
                                        "name", s.get("name"),
                                        "className", s.get("className") == null ? "" : s.get("className")))
                                .toList();
                        return AgentDtos.ToolResult.of(Map.of(
                                "ambiguous", true,
                                "candidates", candidates), candidates.size());
                    }

                    Long studentId = ((Number) students.get(0).get("id")).longValue();
                    StringBuilder sql = new StringBuilder("""
                            SELECT a.session_id AS session_id, cg.name AS class_name, c.name AS course_name,
                                   DATE(s.start_at) AS date, a.status AS status, a.checkin_at AS checkin_at
                            FROM attendance a
                            JOIN checkin_session s ON s.id = a.session_id
                            LEFT JOIN class_group cg ON cg.id = s.class_id
                            LEFT JOIN course c ON c.id = s.course_id
                            WHERE a.student_id = ?
                            """);
                    List<Object> args = new ArrayList<>();
                    args.add(studentId);
                    if (classId != null) {
                        sql.append(" AND s.class_id = ?");
                        args.add(classId);
                    }
                    if (courseId != null) {
                        sql.append(" AND s.course_id = ?");
                        args.add(courseId);
                    }
                    LocalDate from = p.date("dateFrom");
                    LocalDate to = p.date("dateTo");
                    if (from != null) {
                        sql.append(" AND s.start_at >= ?");
                        args.add(from.atStartOfDay());
                    }
                    if (to != null) {
                        sql.append(" AND s.start_at < ?");
                        args.add(to.plusDays(1).atStartOfDay());
                    }
                    appendClassScope(sql, args, ctx);
                    sql.append(" ORDER BY s.start_at DESC LIMIT 200");
                    List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
                    return AgentDtos.ToolResult.of(Map.of(
                            "student", Map.of(
                                    "studentNo", students.get(0).get("studentNo"),
                                    "name", students.get(0).get("name"),
                                    "className", students.get(0).get("className") == null
                                            ? "" : students.get(0).get("className")),
                            "records", rows), rows.size());
                })
                .build();
    }

    // ============================ 2. get_session_summary ============================

    private ToolSpec getSessionSummary() {
        Map<String, Object> schema = ToolSpec.object(Map.of("sessionId", ToolSpec.int64("会话 id")),
                List.of("sessionId"));
        return ToolSpec.builder("get_session_summary", "L0", "查询单场签到汇总")
                .description("一场签到的应到/实到/迟到/缺勤与出勤率。出勤率 =(PRESENT+LATE)/应到。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long sessionId = p.requireLng("sessionId");
                    Map<String, Object> s = requireSession(ctx, sessionId);
                    Map<String, Long> counts = attendanceCounts(sessionId);
                    long expected = expectedCount(sessionId);
                    long present = counts.getOrDefault("PRESENT", 0L);
                    long late = counts.getOrDefault("LATE", 0L);
                    long total = expected > 0 ? expected : counts.values().stream().mapToLong(Long::longValue).sum();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sessionId", sessionId);
                    data.put("className", s.get("className"));
                    data.put("courseName", s.get("courseName"));
                    data.put("status", s.get("status"));
                    data.put("startAt", s.get("startAt"));
                    data.put("total", total);
                    data.put("present", present);
                    data.put("late", late);
                    data.put("absent", counts.getOrDefault("ABSENT", 0L));
                    data.put("leave", counts.getOrDefault("LEAVE", 0L));
                    data.put("exempt", counts.getOrDefault("EXEMPT", 0L));
                    data.put("rate", rate(present + late, total));
                    return AgentDtos.ToolResult.single(data);
                })
                .build();
    }

    // ============================ 3. list_sessions ============================

    private ToolSpec listSessions() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "dateFrom", ToolSpec.date("起始"),
                "dateTo", ToolSpec.date("结束"),
                "classId", ToolSpec.int64("班级"),
                "courseId", ToolSpec.int64("课程")), List.of("dateFrom", "dateTo"));
        return ToolSpec.builder("list_sessions", "L0", "查询签到会话列表")
                .description("按时间范围列出签到场次，可按班级或课程过滤。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    LocalDate from = p.date("dateFrom");
                    LocalDate to = p.date("dateTo");
                    Long classId = p.lng("classId");
                    Long courseId = p.lng("courseId");
                    if (classId != null) {
                        requireClass(ctx, classId);
                    }
                    if (courseId != null) {
                        requireCourse(ctx, courseId);
                    }
                    StringBuilder sql = new StringBuilder("""
                            SELECT s.id AS session_id, cg.name AS class_name, c.name AS course_name,
                                   s.start_at AS start_at, s.end_at AS end_at, s.status AS status,
                                   s.title AS title
                            FROM checkin_session s
                            LEFT JOIN class_group cg ON cg.id = s.class_id
                            LEFT JOIN course c ON c.id = s.course_id
                            WHERE s.start_at >= ? AND s.start_at < ?
                            """);
                    List<Object> args = new ArrayList<>();
                    args.add(from.atStartOfDay());
                    args.add(to.plusDays(1).atStartOfDay());
                    if (classId != null) {
                        sql.append(" AND s.class_id = ?");
                        args.add(classId);
                    }
                    if (courseId != null) {
                        sql.append(" AND s.course_id = ?");
                        args.add(courseId);
                    }
                    appendClassScope(sql, args, ctx);
                    sql.append(" ORDER BY s.start_at DESC LIMIT 200");
                    List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
                    return AgentDtos.ToolResult.of(Map.of("items", rows), rows.size());
                })
                .build();
    }

    // ============================ 4. get_student_profile ============================

    private ToolSpec getStudentProfile() {
        Map<String, Object> schema = ToolSpec.object(Map.of("studentNo", ToolSpec.str("学号")),
                List.of("studentNo"));
        return ToolSpec.builder("get_student_profile", "L0", "查询学生档案")
                .description("只返回姓名、班级、人脸状态、样本数。不含手机号、身份证。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    List<Map<String, Object>> rows = query("""
                            SELECT st.student_no AS student_no, st.name AS name, cg.name AS class_name,
                                   COALESCE(fp.status, 'NONE') AS face_status,
                                   COALESCE(fp.sample_count, 0) AS enroll_count,
                                   st.status AS status
                            FROM student st
                            LEFT JOIN class_group cg ON cg.id = st.class_id
                            LEFT JOIN face_profile fp ON fp.student_id = st.id
                            WHERE st.student_no = ?
                            """, p.requireStr("studentNo"));
                    if (rows.isEmpty()) {
                        return AgentDtos.ToolResult.of(Map.of("found", false), 0);
                    }
                    return AgentDtos.ToolResult.single(rows.get(0));
                })
                .build();
    }

    // ============================ 5. get_class_roster ============================

    private ToolSpec getClassRoster() {
        Map<String, Object> schema = ToolSpec.object(Map.of("classId", ToolSpec.int64("班级")),
                List.of("classId"));
        return ToolSpec.builder("get_class_roster", "L0", "查询班级名册")
                .description("返回班级全部在读学生的学号、姓名与人脸状态。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    requireClass(ctx, classId);
                    List<Map<String, Object>> rows = query("""
                            SELECT st.student_no AS student_no, st.name AS name,
                                   COALESCE(fp.status, 'NONE') AS face_status
                            FROM student st
                            LEFT JOIN face_profile fp ON fp.student_id = st.id
                            WHERE st.class_id = ? AND st.status = 'ACTIVE'
                            ORDER BY st.student_no
                            """, classId);
                    return AgentDtos.ToolResult.of(Map.of("classId", classId, "items", rows), rows.size());
                })
                .build();
    }

    // ============================ 6/7. 聚合统计 ============================

    private ToolSpec getCourseStats() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "courseId", ToolSpec.int64("课程"),
                "dateFrom", ToolSpec.date("起始"),
                "dateTo", ToolSpec.date("结束")), List.of("courseId", "dateFrom", "dateTo"));
        return ToolSpec.builder("get_course_stats", "L0", "按课程聚合统计")
                .description("某课程在时间范围内的出勤率、迟到率与缺勤 TOP。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long courseId = p.requireLng("courseId");
                    requireCourse(ctx, courseId);
                    Map<String, Object> name = queryOne("SELECT name FROM course WHERE id = ?", courseId);
                    Map<String, Object> data = aggregate("s.course_id", courseId,
                            p.date("dateFrom"), p.date("dateTo"), ctx);
                    data.put("courseName", name == null ? "" : name.get("name"));
                    return AgentDtos.ToolResult.single(data);
                })
                .build();
    }

    private ToolSpec getClassStats() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classId", ToolSpec.int64("班级"),
                "dateFrom", ToolSpec.date("起始"),
                "dateTo", ToolSpec.date("结束")), List.of("classId", "dateFrom", "dateTo"));
        return ToolSpec.builder("get_class_stats", "L0", "按班级聚合统计")
                .description("某班级在时间范围内的出勤率、迟到率与缺勤 TOP。口径与课程统计一致。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    requireClass(ctx, classId);
                    Map<String, Object> name = queryOne("SELECT name FROM class_group WHERE id = ?", classId);
                    Map<String, Object> data = aggregate("s.class_id", classId,
                            p.date("dateFrom"), p.date("dateTo"), ctx);
                    data.put("className", name == null ? "" : name.get("name"));
                    return AgentDtos.ToolResult.single(data);
                })
                .build();
    }

    /**
     * 班级/课程共用的聚合口径。抽出来是为了避免两处各写一遍 SQL 后分母悄悄不一致——
     * 「出勤率」这种指标，两个口径就是两个结论。
     */
    private Map<String, Object> aggregate(String dimColumn, long dimId, LocalDate from, LocalDate to,
                                          ToolCtx ctx) {
        List<Object> args = new ArrayList<>();
        args.add(dimId);
        args.add(from.atStartOfDay());
        args.add(to.plusDays(1).atStartOfDay());
        StringBuilder scope = new StringBuilder();
        appendClassScope(scope, args, ctx);

        Map<String, Object> counts = queryOne("""
                SELECT COUNT(*) AS total,
                       SUM(CASE WHEN a.status = 'PRESENT' THEN 1 ELSE 0 END) AS present,
                       SUM(CASE WHEN a.status = 'LATE' THEN 1 ELSE 0 END) AS late,
                       SUM(CASE WHEN a.status = 'ABSENT' THEN 1 ELSE 0 END) AS absent,
                       COUNT(DISTINCT s.id) AS session_count
                FROM attendance a
                JOIN checkin_session s ON s.id = a.session_id
                WHERE %s = ? AND s.start_at >= ? AND s.start_at < ? %s
                """.formatted(dimColumn, scope), args.toArray());

        long total = counts == null || counts.get("total") == null
                ? 0 : ((Number) counts.get("total")).longValue();
        long present = counts == null || counts.get("present") == null
                ? 0 : ((Number) counts.get("present")).longValue();
        long late = counts == null || counts.get("late") == null
                ? 0 : ((Number) counts.get("late")).longValue();

        List<Object> topArgs = new ArrayList<>();
        topArgs.add(dimId);
        topArgs.add(from.atStartOfDay());
        topArgs.add(to.plusDays(1).atStartOfDay());
        StringBuilder topScope = new StringBuilder();
        appendClassScope(topScope, topArgs, ctx);
        List<Map<String, Object>> topAbsent = query("""
                SELECT st.student_no AS student_no, st.name AS name, COUNT(*) AS absent_count
                FROM attendance a
                JOIN checkin_session s ON s.id = a.session_id
                JOIN student st ON st.id = a.student_id
                WHERE %s = ? AND s.start_at >= ? AND s.start_at < ? AND a.status = 'ABSENT' %s
                GROUP BY st.id, st.student_no, st.name
                ORDER BY absent_count DESC, st.student_no
                LIMIT 5
                """.formatted(dimColumn, topScope), topArgs.toArray());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionCount", counts == null ? 0 : ((Number) counts.getOrDefault("sessionCount", 0)).longValue());
        data.put("total", total);
        data.put("present", present);
        data.put("late", late);
        data.put("absent", counts == null || counts.get("absent") == null
                ? 0 : ((Number) counts.get("absent")).longValue());
        data.put("rate", rate(present + late, total));
        data.put("lateRate", rate(late, total));
        data.put("topAbsent", topAbsent);
        return data;
    }

    // ============================ 8. get_attendance_trend ============================

    private ToolSpec getAttendanceTrend() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classId", ToolSpec.int64("班级"),
                "courseId", ToolSpec.int64("课程"),
                "weeks", ToolSpec.integer("最近几周，1~52")), List.of("weeks"));
        return ToolSpec.builder("get_attendance_trend", "L0", "出勤率周趋势")
                .description("按自然周聚合出勤率，返回每周应到与实到人数。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    int weeks = p.integer("weeks", 4);
                    Long classId = p.lng("classId");
                    Long courseId = p.lng("courseId");
                    if (classId == null && courseId == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "classId 与 courseId 至少要有一个");
                    }
                    if (classId != null) {
                        requireClass(ctx, classId);
                    }
                    if (courseId != null) {
                        requireCourse(ctx, courseId);
                    }
                    LocalDate start = LocalDate.now().minusWeeks(Math.max(1, weeks)).with(
                            java.time.DayOfWeek.MONDAY);

                    StringBuilder sql = new StringBuilder("""
                            SELECT DATE_SUB(DATE(s.start_at), INTERVAL WEEKDAY(s.start_at) DAY) AS week_start,
                                   COUNT(DISTINCT s.id) AS session_count,
                                   SUM(CASE WHEN a.status IN ('PRESENT','LATE') THEN 1 ELSE 0 END) AS present,
                                   SUM(CASE WHEN a.status = 'ABSENT' THEN 1 ELSE 0 END) AS absent
                            FROM checkin_session s
                            LEFT JOIN attendance a ON a.session_id = s.id
                            WHERE s.start_at >= ?
                            """);
                    List<Object> args = new ArrayList<>();
                    args.add(start.atStartOfDay());
                    if (classId != null) {
                        sql.append(" AND s.class_id = ?");
                        args.add(classId);
                    }
                    if (courseId != null) {
                        sql.append(" AND s.course_id = ?");
                        args.add(courseId);
                    }
                    appendClassScope(sql, args, ctx);
                    sql.append(" GROUP BY week_start ORDER BY week_start");

                    List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Map<String, Object> r : rows) {
                        long present = r.get("present") == null ? 0 : ((Number) r.get("present")).longValue();
                        long absent = r.get("absent") == null ? 0 : ((Number) r.get("absent")).longValue();
                        long expected = present + absent;
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("weekStart", r.get("weekStart"));
                        item.put("expected", expected);
                        item.put("present", present);
                        item.put("rate", rate(present, expected));
                        out.add(item);
                    }
                    return AgentDtos.ToolResult.of(Map.of("items", out), out.size());
                })
                .build();
    }

    // ============================ 9. list_pending_appeals ============================

    private ToolSpec listPendingAppeals() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classId", ToolSpec.int64("班级"),
                "courseId", ToolSpec.int64("课程")), List.of());
        return ToolSpec.builder("list_pending_appeals", "L0", "待处理申诉列表")
                .description("列出待处理申诉，可按班级或课程过滤。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    Long classId = p.lng("classId");
                    Long courseId = p.lng("courseId");
                    if (classId != null) {
                        requireClass(ctx, classId);
                    }
                    if (courseId != null) {
                        requireCourse(ctx, courseId);
                    }
                    StringBuilder sql = new StringBuilder("""
                            SELECT ap.id AS appeal_id, st.name AS student_name, st.student_no AS student_no,
                                   s.title AS session_title, ap.reason AS reason, ap.created_at AS at,
                                   COALESCE(a.reject_gate, '') AS reject_gate
                            FROM appeal ap
                            JOIN checkin_session s ON s.id = ap.session_id
                            JOIN student st ON st.id = ap.student_id
                            LEFT JOIN attendance a ON a.id = ap.attendance_id
                            WHERE ap.status = 'PENDING'
                            """);
                    List<Object> args = new ArrayList<>();
                    if (classId != null) {
                        sql.append(" AND s.class_id = ?");
                        args.add(classId);
                    }
                    if (courseId != null) {
                        sql.append(" AND s.course_id = ?");
                        args.add(courseId);
                    }
                    appendClassScope(sql, args, ctx);
                    sql.append(" ORDER BY ap.created_at DESC LIMIT 100");
                    List<Map<String, Object>> rows = query(sql.toString(), args.toArray());
                    return AgentDtos.ToolResult.of(Map.of("items", rows), rows.size());
                })
                .build();
    }

    // ============================ 公共查询辅助 ============================

    Map<String, Object> requireSession(ToolCtx ctx, long sessionId) {
        Map<String, Object> s = queryOne("""
                SELECT s.id AS session_id, s.class_id AS class_id, s.course_id AS course_id,
                       cg.name AS class_name, c.name AS course_name, s.status AS status,
                       s.start_at AS start_at, s.end_at AS end_at, s.title AS title
                FROM checkin_session s
                LEFT JOIN class_group cg ON cg.id = s.class_id
                LEFT JOIN course c ON c.id = s.course_id
                WHERE s.id = ?
                """, sessionId);
        if (s == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        requireClass(ctx, ((Number) s.get("classId")).longValue());
        return s;
    }

    Map<String, Long> attendanceCounts(long sessionId) {
        List<Map<String, Object>> rows = query(
                "SELECT status, COUNT(*) AS c FROM attendance WHERE session_id = ? GROUP BY status",
                sessionId);
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            out.put(String.valueOf(r.get("status")), ((Number) r.get("c")).longValue());
        }
        return out;
    }

    long expectedCount(long sessionId) {
        Map<String, Object> row = queryOne(
                "SELECT COUNT(*) AS c FROM session_expected WHERE session_id = ?", sessionId);
        return row == null ? 0 : ((Number) row.get("c")).longValue();
    }

    /** 某班级「现在」的在读学生数。用于发起签到前的应到人数预览（不是历史快照）。 */
    long expectedCount0(long classId) {
        Map<String, Object> row = queryOne(
                "SELECT COUNT(*) AS c FROM student WHERE class_id = ? AND status = 'ACTIVE'", classId);
        return row == null ? 0 : ((Number) row.get("c")).longValue();
    }

    void requireClass(ToolCtx ctx, long classId) {
        if (!ctx.principal().allowsClass(classId)) {
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED, "你当前的数据范围不包含该班级");
        }
    }

    void requireCourse(ToolCtx ctx, long courseId) {
        if (!ctx.principal().allowsCourse(courseId)) {
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED, "你当前的数据范围不包含该课程");
        }
    }

    /** 列表类查询的范围过滤：教师只看得到授权班级的场次；管理员不加限制。 */
    void appendClassScope(StringBuilder sql, List<Object> args, ToolCtx ctx) {
        AgentPrincipal principal = ctx.principal();
        if (principal.isAdmin()) {
            return;
        }
        var ids = principal.classIds();
        if (ids.isEmpty()) {
            sql.append(" AND 1 = 0");
            return;
        }
        sql.append(" AND s.class_id IN (")
                .append(String.join(",", java.util.Collections.nCopies(ids.size(), "?")))
                .append(")");
        args.addAll(ids);
    }

    List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> toMap(rs.getMetaData(), rs), args);
    }

    Map<String, Object> queryOne(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 列名转 camelCase，并统一时间类型。
     *
     * <p>为什么统一在这里做：MySQL 驱动对 DATE 返回 {@code java.sql.Date}、对 DATETIME 返回
     * {@code java.sql.Timestamp}，直接丢给 Jackson 会得到两种不同的序列化形态，
     * Agent 侧解析会随机出错。这里一次转成 {@link LocalDate}/{@link LocalDateTime}。
     */
    private Map<String, Object> toMap(ResultSetMetaData meta, java.sql.ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        int cols = meta.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String label = meta.getColumnLabel(i);
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Timestamp ts) {
                value = ts.toLocalDateTime();
            } else if (value instanceof java.sql.Date d) {
                value = d.toLocalDate();
            } else if (value instanceof BigDecimal bd) {
                value = bd.doubleValue();
            }
            row.put(camel(label), value);
        }
        return row;
    }

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    static double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
