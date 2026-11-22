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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

    /**
     * 导出 Excel（.xlsx）。口径与列表完全一致（同一段 where、同一排序），
     * 否则「页面看到的」和「导出拿到的」会对不上——对账类功能最常见的不信任来源。
     *
     * <p>为什么不再导 CSV：CSV 是纯文本，文件里存不了任何颜色/底纹信息；
     * 教师要求打开文件就能一眼认出缺勤学生，只能换成 Excel——缺勤（ABSENT）行整行红底高亮。
     *
     * <p>单场查询时以应到名单为基表，未签到的人 COALESCE 成 ABSENT（见 SELECT_FROM_EXPECTED），
     * 缺勤的人不会因为「还没签到、没有 attendance 记录」而在导出里消失。
     */
    @DataScope
    public byte[] exportXlsx(Long sessionId, Long classId, Long courseId, LocalDate from, LocalDate to) {
        return exportXlsxFile(sessionId, classId, courseId, from, to).bytes();
    }

    /**
     * 同上，但把行数一并返回。
     *
     * <p>Agent 的回答里要写「共 N 行」——不能让它去数字节数或者猜；
     * 空表也要如实说「范围内没有记录」，而不是给一个 0 字节文件。
     */
    @DataScope
    public ExportFile exportXlsxFile(Long sessionId, Long classId, Long courseId,
                                     LocalDate from, LocalDate to) {
        Filter f = filter(sessionId, classId, courseId, null, null, from, to);
        String select = sessionId != null ? SELECT_FROM_EXPECTED : SELECT_FROM_ATTENDANCE;
        List<AttendanceDtos.Row> rows = f.blocked() ? List.of()
                : jdbc.query(select + f.where() + " ORDER BY st.student_no", mapper(), f.args.toArray());
        return new ExportFile(toXlsx(rows), rows.size());
    }

    /** 导出结果：文件字节 + 数据行数（不含表头）。 */
    public record ExportFile(byte[] bytes, int rows) {
    }

    /**
     * 导出列：只留「谁来了、谁没来、为什么改状态」。
     *
     * <p><b>相似度 / 活体 / 身份三个分值故意不进导出。</b>它们是排障与申诉裁定用的内部指标：
     * 进了教师、教务手里的表，就会被当成「按概率判定签到是否有效」的依据（出现过拿 0.63
     * 来质问「为什么这也算签到成功」）；而且分值随模型换版失去可比性，留在文件里等于留下
     * 一份无法解释的历史数据。管理端列表仍显示这三项——需要看分数的是处理申诉的人，
     * 不是在 Excel 里对账的人。列上的差异只有这三列，行范围与排序仍与列表完全一致。
     */
    private static final String[] XLSX_HEADERS = {
            "学号", "姓名", "班级", "课程", "场次", "状态", "签到时间", "调整原因"
    };

    /** 列宽（字符数 × 256），手调过：中文表头不换行、原因列够宽。 */
    private static final int[] XLSX_WIDTHS = {12, 10, 14, 14, 22, 10, 20, 28};

    /**
     * 生成工作簿。缺勤行用 Excel「坏值」经典配色：浅红底（ROSE）+ 深红字（DARK_RED）。
     * 包级私有：单元测试直接喂行数据断言样式，不碰数据库。
     */
    static byte[] toXlsx(List<AttendanceDtos.Row> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            XSSFCellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // 导出已无小数列（人脸分值不导出，见 XLSX_HEADERS），因此只需要文本与时间两种格式。
            short tsFmt = wb.createDataFormat().getFormat("yyyy-MM-dd HH:mm:ss");
            XSSFCellStyle textStyle = wb.createCellStyle();
            XSSFCellStyle tsStyle = wb.createCellStyle();
            tsStyle.setDataFormat(tsFmt);

            XSSFFont absentFont = wb.createFont();
            absentFont.setColor(IndexedColors.DARK_RED.getIndex());
            XSSFCellStyle absentText = wb.createCellStyle();
            absentText.setFont(absentFont);
            absentText.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            absentText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            XSSFCellStyle absentTs = wb.createCellStyle();
            absentTs.cloneStyleFrom(absentText);
            absentTs.setDataFormat(tsFmt);

            XSSFSheet sheet = wb.createSheet("考勤记录");
            XSSFRow head = sheet.createRow(0);
            for (int i = 0; i < XLSX_HEADERS.length; i++) {
                XSSFCell cell = head.createCell(i);
                cell.setCellValue(XLSX_HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 1);
            for (int i = 0; i < XLSX_WIDTHS.length; i++) {
                sheet.setColumnWidth(i, XLSX_WIDTHS[i] * 256);
            }

            int r = 1;
            for (AttendanceDtos.Row row : rows) {
                boolean absent = "ABSENT".equals(row.status());
                // 每 cell 一个样式，样式对象全程只有固定 4 个 —— 绝不能在循环里 createCellStyle，
                // 否则超量行会撞上 xlsx 的 64000 样式上限。
                XSSFCellStyle[] styles = absent
                        ? new XSSFCellStyle[]{absentText, absentText, absentText, absentText,
                              absentText, absentText, absentTs, absentText}
                        : new XSSFCellStyle[]{textStyle, textStyle, textStyle, textStyle,
                              textStyle, textStyle, tsStyle, textStyle};
                XSSFRow line = sheet.createRow(r++);
                text(line, 0, row.studentNo(), styles[0]);
                text(line, 1, row.studentName(), styles[1]);
                text(line, 2, row.className(), styles[2]);
                text(line, 3, row.courseName(), styles[3]);
                text(line, 4, row.sessionTitle(), styles[4]);
                text(line, 5, row.status(), styles[5]);
                if (row.checkinAt() != null) {
                    XSSFCell cell = line.createCell(6);
                    cell.setCellValue(java.sql.Timestamp.valueOf(row.checkinAt()));
                    cell.setCellStyle(styles[6]);
                } else {
                    line.createCell(6).setCellStyle(styles[6]);
                }
                text(line, 7, row.adjustReason(), styles[7]);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "生成 Excel 文件失败");
        }
    }

    private static void text(XSSFRow row, int col, String value, XSSFCellStyle style) {
        XSSFCell cell = row.createCell(col);
        if (value != null && !value.isEmpty()) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
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
