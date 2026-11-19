package com.fa.checkin.app;

import com.fa.checkin.api.dto.AttendanceDtos;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.DataScope;
import com.fa.common.security.ScopeContext;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端考勤查询与调整。
 *
 * <p>为什么不复用 MyBatis-Plus 的实体查询：这里要 join 四张表（会话/学生/班级/课程）
 * 再按教师范围过滤，用一段 SQL 比拼 Wrapper 更直白，也更容易看清索引怎么走。
 *
 * <p>导出口径与列表完全一致（同一段 where），否则「页面看到的」和「导出拿到的」
 * 会对不上——这是对账类功能最常见的不信任来源。
 */
@Service
public class AttendanceQueryService {

    private static final Set<String> STATUSES =
            Set.of("PRESENT", "LATE", "ABSENT", "LEAVE", "EXEMPT");

    /**
     * 历史查询：以 attendance 为基表（只返回真正产生过记录的人）。
     *
     * <p>JOIN 顺序固定为「会话 → 学生 → 班级/课程」，别改成从 attendance 出发再回查，
     * 那样教师范围过滤会退化成子查询。
     */
    private static final String SELECT_FROM_ATTENDANCE = """
            SELECT a.id, a.session_id, s.title AS session_title, s.class_id, c.name AS class_name,
                   s.course_id, k.name AS course_name, a.student_id, st.student_no,
                   st.name AS student_name, a.status, a.checkin_at, a.similarity,
                   a.liveness_median, a.identity_sim, a.action_count, a.action_seq,
                   a.reject_gate, a.adjust_reason, a.adjusted_by
            FROM attendance a
            JOIN checkin_session s ON s.id = a.session_id
            JOIN student st ON st.id = a.student_id
            LEFT JOIN class_group c ON c.id = s.class_id
            LEFT JOIN course k ON k.id = s.course_id
            """;

    /**
     * 单场查询：以 session_expected 为基表，LEFT JOIN attendance。
     *
     * <p>为什么不能只查 attendance：签到是「签了才建记录」，进行中的会话里
     * 未签到的人压根不在表中。大屏要显示「应到/未到」，必须从冻结的应到名单出发，
     * 否则永远显示应到 0（B5 演示时实际踩到过）。
     *
     * <p>未签到的人 a.id 为 NULL —— 前端据此禁用「调整」按钮，
     * 需要标记请假时走 Agent 的 mark_leave（upsert）或等会话结束补 ABSENT。
     */
    private static final String SELECT_FROM_EXPECTED = """
            SELECT a.id, e.session_id, s.title AS session_title, s.class_id, c.name AS class_name,
                   s.course_id, k.name AS course_name, e.student_id, st.student_no,
                   st.name AS student_name, COALESCE(a.status, 'ABSENT') AS status,
                   a.checkin_at, a.similarity, a.liveness_median, a.identity_sim,
                   a.action_count, a.action_seq, a.reject_gate, a.adjust_reason, a.adjusted_by
            FROM session_expected e
            JOIN checkin_session s ON s.id = e.session_id
            JOIN student st ON st.id = e.student_id
            LEFT JOIN attendance a ON a.session_id = e.session_id AND a.student_id = e.student_id
            LEFT JOIN class_group c ON c.id = s.class_id
            LEFT JOIN course k ON k.id = s.course_id
            """;

    private final JdbcTemplate jdbc;
    private final AuditWriter audit;

    public AttendanceQueryService(JdbcTemplate jdbc, AuditWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @DataScope
    public PageResult<AttendanceDtos.Row> list(PageQuery query, Long sessionId, Long classId,
                                               Long courseId, String status, String studentKeyword,
                                               LocalDate from, LocalDate to) {
        Filter f = filter(sessionId, classId, courseId, status, studentKeyword, from, to);
        if (f.blocked()) {
            return PageResult.empty(query);
        }
        String fromClause = sessionId != null
                ? " FROM session_expected e"
                  + " JOIN checkin_session s ON s.id = e.session_id"
                  + " JOIN student st ON st.id = e.student_id"
                  + " LEFT JOIN attendance a ON a.session_id = e.session_id"
                  + " AND a.student_id = e.student_id"
                : " FROM attendance a"
                  + " JOIN checkin_session s ON s.id = a.session_id"
                  + " JOIN student st ON st.id = a.student_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + fromClause + f.where(),
                Long.class, f.args.toArray());
        if (total == null || total == 0) {
            return PageResult.empty(query);
        }
        List<Object> args = new ArrayList<>(f.args);
        args.add(query.size());
        args.add(query.offset());
        String select = sessionId != null ? SELECT_FROM_EXPECTED : SELECT_FROM_ATTENDANCE;
        List<AttendanceDtos.Row> items = jdbc.query(select + f.where()
                + " ORDER BY st.student_no LIMIT ? OFFSET ?", mapper(), args.toArray());
        return PageResult.of(items, total, query);
    }

    @DataScope
    public String exportCsv(Long sessionId, Long classId, Long courseId, LocalDate from, LocalDate to) {
        Filter f = filter(sessionId, classId, courseId, null, null, from, to);
        StringBuilder sb = new StringBuilder("\uFEFF");  // BOM：Excel 直接双击不乱码
        sb.append("学号,姓名,班级,课程,场次,状态,签到时间,相似度,活体,身份,调整原因\n");
        if (f.blocked()) {
            return sb.toString();
        }
        String select = sessionId != null ? SELECT_FROM_EXPECTED : SELECT_FROM_ATTENDANCE;
        jdbc.query(select + f.where() + " ORDER BY st.student_no", rs -> {
            // RowCallbackHandler：游标已在当前行，写一行即可
            sb.append(csv(rs.getString("student_no"))).append(',')
              .append(csv(rs.getString("student_name"))).append(',')
              .append(csv(rs.getString("class_name"))).append(',')
              .append(csv(rs.getString("course_name"))).append(',')
              .append(csv(rs.getString("session_title"))).append(',')
              .append(csv(rs.getString("status"))).append(',')
              .append(csv(fmt(rs.getTimestamp("checkin_at")))).append(',')
              .append(csv(rs.getBigDecimal("similarity"))).append(',')
              .append(csv(rs.getBigDecimal("liveness_median"))).append(',')
              .append(csv(rs.getBigDecimal("identity_sim"))).append(',')
              .append(csv(rs.getString("adjust_reason"))).append('\n');
        }, f.args.toArray());
        return sb.toString();
    }

    @Transactional
    public AttendanceDtos.Row patch(long id, AttendanceDtos.PatchReq req) {
        if (req == null || req.status() == null || !STATUSES.contains(req.status())) {
            throw new BizException(ErrorCode.NOT_FOUND, "状态取值不合法");
        }
        String reason = req.adjustReason() == null ? "" : req.adjustReason().trim();
        if (reason.length() < 2 || reason.length() > 255) {
            throw new BizException(ErrorCode.NOT_FOUND, "调整原因需 2~255 字，且会长期留存在记录上");
        }
        List<Long> classIds = jdbc.query(
                "SELECT s.class_id FROM attendance a JOIN checkin_session s ON s.id = a.session_id"
                        + " WHERE a.id = ?", (rs, i) -> rs.getLong(1), id);
        if (classIds.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "考勤记录不存在");
        }
        Long actorId = SecurityUtils.currentOrNull() == null
                ? null : SecurityUtils.current().accountId();
        jdbc.update("""
                UPDATE attendance SET status = ?, adjust_reason = ?, adjusted_by = ? WHERE id = ?
                """, req.status(), reason, actorId, id);
        audit.write("ATTENDANCE_ADJUST", "ATTENDANCE", id, "→ " + req.status() + "：" + reason);
        return jdbc.query(SELECT_FROM_ATTENDANCE + " WHERE a.id = ?", mapper(), id).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "考勤记录不存在"));
    }

    private Filter filter(Long sessionId, Long classId, Long courseId, String status,
                          String studentKeyword, LocalDate from, LocalDate to) {
        Set<Long> allowed = ScopeContext.current().classFilter();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (allowed != null && allowed.isEmpty()) {
            return new Filter(where, args, true);
        }
        if (allowed != null) {
            where.append(" AND s.class_id IN (").append(placeholders(allowed.size())).append(")");
            args.addAll(allowed);
        }
        if (sessionId != null) {
            // 有 sessionId 就一定走 session_expected 基表：未签到的人 a.* 全是 NULL，
            // 用 a.session_id 过滤会把「应到但没签」的人整批过滤掉，大屏就成了应到 0。
            where.append(" AND e.session_id = ?");
            args.add(sessionId);
        }
        if (classId != null) {
            where.append(" AND s.class_id = ?");
            args.add(classId);
        }
        if (courseId != null) {
            where.append(" AND s.course_id = ?");
            args.add(courseId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND a.status = ?");
            args.add(status.trim());
        }
        if (studentKeyword != null && !studentKeyword.isBlank()) {
            where.append(" AND (st.name LIKE ? OR st.student_no LIKE ?)");
            String kw = "%" + studentKeyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        if (from != null) {
            where.append(" AND s.start_at >= ?");
            args.add(from.atStartOfDay());
        }
        if (to != null) {
            where.append(" AND s.start_at < ?");
            args.add(to.plusDays(1).atStartOfDay());
        }
        return new Filter(where, args, false);
    }

    private RowMapper<AttendanceDtos.Row> mapper() {
        return (rs, i) -> new AttendanceDtos.Row(
                nullableLong(rs, "id"),
                rs.getLong("session_id"),
                rs.getString("session_title"),
                nullableLong(rs, "class_id"),
                rs.getString("class_name"),
                nullableLong(rs, "course_id"),
                rs.getString("course_name"),
                rs.getLong("student_id"),
                rs.getString("student_no"),
                rs.getString("student_name"),
                rs.getString("status"),
                rs.getTimestamp("checkin_at") == null ? null : rs.getTimestamp("checkin_at").toLocalDateTime(),
                rs.getBigDecimal("similarity") == null ? null : rs.getBigDecimal("similarity").doubleValue(),
                rs.getBigDecimal("liveness_median") == null ? null : rs.getBigDecimal("liveness_median").doubleValue(),
                rs.getBigDecimal("identity_sim") == null ? null : rs.getBigDecimal("identity_sim").doubleValue(),
                (Integer) rs.getObject("action_count"),
                rs.getString("action_seq"),
                rs.getString("reject_gate"),
                rs.getString("adjust_reason"),
                nullableLong(rs, "adjusted_by"));
    }

    /** CSV 转义：逗号/引号/换行都要包起来，否则导出后列会错位。 */
    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String fmt(java.sql.Timestamp ts) {
        return ts == null ? "" : ts.toLocalDateTime().toString().replace('T', ' ');
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    /** 注意组件名用 sql 而不是 where：record 的 accessor 不能改返回类型。 */
    private record Filter(StringBuilder sql, List<Object> args, boolean blocked) {
        String where() {
            return sql.toString();
        }
    }
}
