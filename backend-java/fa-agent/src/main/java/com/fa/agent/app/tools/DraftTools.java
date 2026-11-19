package com.fa.agent.app.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa.agent.api.dto.AgentDtos;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * L1 草稿工具：<b>只算不写</b>。
 *
 * <p>这一层的价值在于把「会发生什么」先摆到桌面上：
 * 导入前告诉你「新增 12 / 更新 3 / 失败 2（第 7 行学号重复）」，
 * 发起签到前告诉你「应到 40 人，其中 2 人未通过人脸审核，将无法签到」。
 * 没有这一层，用户只能在写完之后才发现问题。
 *
 * <p>草稿存 Redis 15 分钟（契约 levels.L1.note）。<b>不入库</b>——
 * 草稿是过程产物，落库会带来「草稿表要不要清理、要不要审计」的一堆问题，
 * 而这些数据 15 分钟后就没有价值了。
 */
@Component
public class DraftTools {

    private static final String DRAFT_PREFIX = "agent:draft:";
    private static final Duration DRAFT_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final QueryTools queries;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public DraftTools(JdbcTemplate jdbc, QueryTools queries, StringRedisTemplate redis,
                      ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public List<ToolSpec> specs() {
        return List.of(draftSession(), draftAbsentList(), draftAppealReply(),
                summarizeSession(), draftImportStudents());
    }

    // ============================ draft_session ============================

    private ToolSpec draftSession() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classId", ToolSpec.int64("班级"),
                "courseId", ToolSpec.int64("课程"),
                "durationMinutes", ToolSpec.integer("时长（分钟），默认 10"),
                "lateAfterSec", ToolSpec.integer("迟到阈值（秒），默认 300")),
                List.of("classId", "courseId"));
        return ToolSpec.builder("draft_session", "L1", "生成签到参数草稿")
                .description("只算参数与应到人数，不建会话。用户确认后再走 create_session。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long classId = p.requireLng("classId");
                    long courseId = p.requireLng("courseId");
                    queries.requireClass(ctx, classId);
                    queries.requireCourse(ctx, courseId);

                    Map<String, Object> clazz = queries.queryOne(
                            "SELECT id, code, name, status FROM class_group WHERE id = ?", classId);
                    Map<String, Object> course = queries.queryOne(
                            "SELECT id, code, name, status FROM course WHERE id = ?", courseId);
                    if (clazz == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "班级不存在");
                    }
                    if (course == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
                    }
                    if (!"ACTIVE".equals(clazz.get("status")) || !"ACTIVE".equals(course.get("status"))) {
                        throw new BizException(ErrorCode.ORG_ARCHIVED, "班级或课程已归档，不能发起签到");
                    }

                    Map<String, Object> count = queries.queryOne("""
                            SELECT COUNT(*) AS expected,
                                   SUM(CASE WHEN COALESCE(fp.status, 'NONE') <> 'ACTIVE' THEN 1 ELSE 0 END) AS not_active
                            FROM student st
                            LEFT JOIN face_profile fp ON fp.student_id = st.id
                            WHERE st.class_id = ? AND st.status = 'ACTIVE'
                            """, classId);
                    long expected = count == null ? 0 : ((Number) count.get("expected")).longValue();
                    long notActive = count == null || count.get("not_active") == null
                            ? 0 : ((Number) count.get("not_active")).longValue();

                    List<String> warnings = new ArrayList<>();
                    if (expected == 0) {
                        warnings.add("该班级没有在读学生，发起后将无人可签");
                    }
                    if (notActive > 0) {
                        warnings.add("该班级有 " + notActive + " 名学生人脸未审核通过，将无法签到");
                    }

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("className", clazz.get("name"));
                    data.put("courseName", course.get("name"));
                    data.put("expectedCount", expected);
                    data.put("durationMinutes", p.integer("durationMinutes", 10));
                    data.put("lateAfterSec", p.integer("lateAfterSec", 300));
                    data.put("warnings", warnings);
                    return AgentDtos.ToolResult.single(data);
                })
                .build();
    }

    // ============================ draft_absent_list ============================

    private ToolSpec draftAbsentList() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "sessionId", ToolSpec.int64("会话"),
                "classId", ToolSpec.int64("班级"),
                "dateFrom", ToolSpec.date("起始"),
                "dateTo", ToolSpec.date("结束")), List.of());
        return ToolSpec.builder("draft_absent_list", "L1", "生成缺勤名单")
                .description("按会话或班级+时间段生成缺勤名单，返回行数据，不落库。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    Long sessionId = p.lng("sessionId");
                    StringBuilder sql = new StringBuilder("""
                            SELECT st.student_no AS student_no, st.name AS name, cg.name AS class_name,
                                   c.name AS course_name, DATE(s.start_at) AS date, a.status AS status
                            FROM attendance a
                            JOIN checkin_session s ON s.id = a.session_id
                            JOIN student st ON st.id = a.student_id
                            LEFT JOIN class_group cg ON cg.id = s.class_id
                            LEFT JOIN course c ON c.id = s.course_id
                            WHERE a.status = 'ABSENT'
                            """);
                    List<Object> args = new ArrayList<>();
                    if (sessionId != null) {
                        queries.requireSession(ctx, sessionId);
                        sql.append(" AND a.session_id = ?");
                        args.add(sessionId);
                    } else {
                        Long classId = p.lng("classId");
                        if (classId == null) {
                            throw new BizException(ErrorCode.NOT_FOUND, "sessionId 与 classId 至少要有一个");
                        }
                        queries.requireClass(ctx, classId);
                        sql.append(" AND s.class_id = ?");
                        args.add(classId);
                        var from = p.date("dateFrom");
                        var to = p.date("dateTo");
                        if (from != null) {
                            sql.append(" AND s.start_at >= ?");
                            args.add(from.atStartOfDay());
                        }
                        if (to != null) {
                            sql.append(" AND s.start_at < ?");
                            args.add(to.plusDays(1).atStartOfDay());
                        }
                    }
                    queries.appendClassScope(sql, args, ctx);
                    sql.append(" ORDER BY s.start_at DESC, st.student_no LIMIT 500");
                    List<Map<String, Object>> rows = queries.query(sql.toString(), args.toArray());

                    String draftId = storeDraft(Map.of("kind", "absent", "rows", rows));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("draftId", draftId);
                    data.put("count", rows.size());
                    data.put("rows", rows);
                    return AgentDtos.ToolResult.of(data, rows.size());
                })
                .build();
    }

    // ============================ draft_appeal_reply ============================

    private ToolSpec draftAppealReply() {
        Map<String, Object> schema = ToolSpec.object(Map.of("appealId", ToolSpec.int64("申诉")),
                List.of("appealId"));
        return ToolSpec.builder("draft_appeal_reply", "L1", "起草申诉回复")
                .description("根据失败门禁与重试记录生成处理意见与文案。必须由教师确认后才提交。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long appealId = p.requireLng("appealId");
                    Map<String, Object> a = queries.queryOne("""
                            SELECT ap.id AS appeal_id, ap.status AS status, ap.reason AS reason,
                                   ap.session_id AS session_id, s.class_id AS class_id,
                                   st.name AS student_name, st.student_no AS student_no,
                                   COALESCE(att.reject_gate, '') AS reject_gate,
                                   COALESCE(att.status, '') AS attendance_status,
                                   att.similarity AS similarity, att.action_count AS action_count,
                                   s.title AS session_title
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
                        throw new BizException(ErrorCode.APPEAL_EXISTS, "该申诉已处理，无需再起草回复");
                    }

                    String gate = String.valueOf(a.get("rejectGate"));
                    String studentName = String.valueOf(a.get("studentName"));
                    boolean approve = gate.isEmpty() || "similarity".equals(gate) || "no_face".equals(gate);
                    String reply = approve
                            ? studentName + "同学：已核实本次签到失败原因（" + gateLabel(gate)
                                    + "），情况属实，本次考勤已调整为出勤。"
                            : studentName + "同学：经复核，本次签到未通过" + gateLabel(gate)
                                    + "，判定依据充分，本次申诉不予通过。如有疑问请到办公室说明。";
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("suggestedDecision", approve ? "APPROVE" : "REJECT");
                    data.put("reply", reply);
                    data.put("rationale", "rejectGate=" + (gate.isEmpty() ? "无记录" : gate)
                            + "，原状态=" + a.get("attendanceStatus")
                            + "，动作次数=" + (a.get("actionCount") == null ? "-" : a.get("actionCount")));
                    data.put("rejectGate", gate.isEmpty() ? null : gate);
                    return AgentDtos.ToolResult.single(data);
                })
                .build();
    }

    private static String gateLabel(String gate) {
        return switch (gate == null ? "" : gate) {
            case "similarity" -> "人脸比对相似度不足";
            case "no_face" -> "画面中未检测到人脸";
            case "action" -> "指定动作未完成";
            case "liveness" -> "活体校验未通过";
            case "identity" -> "首末帧身份不一致";
            case "multi_face" -> "画面中出现多个人脸";
            case "" -> "无失败门禁记录";
            default -> gate;
        };
    }

    // ============================ summarize_session ============================

    private ToolSpec summarizeSession() {
        Map<String, Object> schema = ToolSpec.object(Map.of("sessionId", ToolSpec.int64("会话")),
                List.of("sessionId"));
        return ToolSpec.builder("summarize_session", "L1", "生成签到小结")
                .description("生成一场签到的文字小结（应到/实到/迟到/异常）。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    long sessionId = p.requireLng("sessionId");
                    Map<String, Object> s = queries.requireSession(ctx, sessionId);
                    Map<String, Long> counts = queries.attendanceCounts(sessionId);
                    long expected = queries.expectedCount(sessionId);
                    long present = counts.getOrDefault("PRESENT", 0L);
                    long late = counts.getOrDefault("LATE", 0L);
                    long absent = counts.getOrDefault("ABSENT", 0L);
                    long leave = counts.getOrDefault("LEAVE", 0L);
                    double rate = QueryTools.rate(present + late, expected);
                    String text = "%s × %s：应到 %d 人，实到 %d 人（含迟到 %d 人），缺勤 %d 人，请假 %d 人，出勤率 %.2f%%。"
                            .formatted(s.get("className"), s.get("courseName"), expected,
                                    present + late, late, absent, leave, rate * 100);
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("total", expected);
                    stats.put("present", present);
                    stats.put("late", late);
                    stats.put("absent", absent);
                    return AgentDtos.ToolResult.of(Map.of("text", text, "stats", stats), 1);
                })
                .build();
    }

    // ============================ draft_import_students ============================

    private ToolSpec draftImportStudents() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "classCode", ToolSpec.str("班级码"),
                "csvText", ToolSpec.str("CSV 全文，表头 studentNo,name")),
                List.of("classCode", "csvText"));
        return ToolSpec.builder("draft_import_students", "L1", "预览学生导入")
                .description("解析 CSV，返回将新增 N / 更新 M / 失败 K（含行号与原因）。不写库。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    String classCode = p.requireStr("classCode");
                    String csv = p.requireStr("csvText");
                    Map<String, Object> clazz = queries.queryOne(
                            "SELECT id, name, status FROM class_group WHERE code = ?", classCode);
                    if (clazz == null) {
                        throw new BizException(ErrorCode.NOT_FOUND, "班级码无效");
                    }
                    long classId = ((Number) clazz.get("id")).longValue();
                    queries.requireClass(ctx, classId);

                    Preview preview = parseCsv(classId, csv);
                    String draftId = storeDraft(Map.of(
                            "kind", "import",
                            "classCode", classCode,
                            "csvText", csv));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("draftId", draftId);
                    data.put("className", clazz.get("name"));
                    data.put("total", preview.total());
                    data.put("willCreate", preview.create());
                    data.put("willUpdate", preview.update());
                    data.put("failed", preview.failed());
                    data.put("errors", preview.errors());
                    return AgentDtos.ToolResult.of(data, preview.total());
                })
                .build();
    }

    record Preview(int total, int create, int update, int failed, List<Map<String, Object>> errors) {
    }

    /**
     * 纯预览解析：只统计，不写库。
     *
     * <p>与 {@code StudentService.importCsv} 用同一套表头与行号规则（行号把表头算进去），
     * 否则「预览说第 7 行错、执行却报第 6 行」，教师会以为系统在骗人。
     */
    Preview parseCsv(long classId, String csv) {
        int total = 0;
        int create = 0;
        int update = 0;
        int failed = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv), 8192)) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader().setSkipHeaderRecord(true).setTrim(true).setIgnoreEmptyLines(true)
                    .build();
            try (CSVParser parser = CSVParser.parse(reader, format)) {
                for (CSVRecord record : parser) {
                    int line = (int) record.getRecordNumber() + 1;
                    total++;
                    String studentNo = record.isMapped("studentNo") ? record.get("studentNo") : null;
                    String name = record.isMapped("name") ? record.get("name") : null;
                    String bad = null;
                    if (studentNo == null || studentNo.isBlank()) {
                        bad = "学号为空";
                    } else if (studentNo.length() > 32) {
                        bad = "学号超过 32 位";
                    } else if (name == null || name.isBlank()) {
                        bad = "姓名为空";
                    } else if (name.length() > 64) {
                        bad = "姓名超过 64 位";
                    } else if (!seen.add(studentNo)) {
                        bad = "学号在文件内重复";
                    }
                    if (bad != null) {
                        failed++;
                        errors.add(Map.of("line", line,
                                "studentNo", studentNo == null ? "" : studentNo,
                                "reason", bad));
                        continue;
                    }
                    Integer exists = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM student WHERE student_no = ?", Integer.class, studentNo);
                    if (exists != null && exists > 0) {
                        update++;
                    } else {
                        create++;
                    }
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "CSV 解析失败：" + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "CSV 缺少表头 studentNo,name");
        }
        return new Preview(total, create, update, failed, errors);
    }

    // ============================ 草稿存取 ============================

    String storeDraft(Map<String, Object> payload) {
        String draftId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            redis.opsForValue().set(DRAFT_PREFIX + draftId,
                    objectMapper.writeValueAsString(payload), DRAFT_TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("草稿序列化失败", e);
        }
        return draftId;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> loadDraft(String draftId) {
        String raw = redis.opsForValue().get(DRAFT_PREFIX + draftId);
        if (raw == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "草稿已过期（15 分钟），请重新生成");
        }
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "草稿已失效，请重新生成");
        }
    }

    void deleteDraft(String draftId) {
        redis.delete(DRAFT_PREFIX + draftId);
    }
}
