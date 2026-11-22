package com.fa.agent.app.tools;

import com.fa.agent.api.dto.AgentDtos;
import com.fa.checkin.app.AttendanceQueryService;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 导出类只读工具。
 *
 * <p><b>为什么导出要走工具而不是让 Agent 自己拼 Excel</b>：
 * 导出口径必须和管理端「导出 Excel」按钮完全一致（同一段 SQL、同一排序、同一套缺勤红底样式）。
 * Agent 侧再实现一遍，两处早晚会漂移——教师拿着两份对不上的表来问，是最难解释的一种 bug。
 *
 * <p><b>为什么把文件内容 base64 回来</b>：Agent 进程不持有数据库凭据，也无法访问 Java 的临时目录；
 * 让 Streamlit 直接渲染 {@code st.download_button} 是唯一不需要再开一个下载端点的做法。
 * 代价是响应体变大，因此这里设了硬上限，宁可让用户缩小范围，也不要把几十 MB 塞进
 * LangGraph 的检查点。
 */
@Component
public class ExportTools {

    static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /**
     * base64 字符数上限（约 3MB 原文件、1.5 万行以内）。
     *
     * <p>这个值不是随手取的：state 会被 LangGraph 每个 super-step 写一次 Redis，
     * 文件越大写入越频繁且没有收益——导出是给人看的，超过这个量的表应该按班级/场次拆。
     */
    static final int MAX_BASE64_CHARS = 4 * 1024 * 1024;

    private final AttendanceQueryService attendance;

    public ExportTools(AttendanceQueryService attendance) {
        this.attendance = attendance;
    }

    public List<ToolSpec> specs() {
        return List.of(exportAttendance());
    }

    private ToolSpec exportAttendance() {
        Map<String, Object> schema = ToolSpec.object(Map.of(
                "sessionId", ToolSpec.int64("单场会话 id，导出这一场"),
                "classId", ToolSpec.int64("限定班级"),
                "courseId", ToolSpec.int64("限定课程"),
                "dateFrom", ToolSpec.date("起始日期（含）"),
                "dateTo", ToolSpec.date("结束日期（含）")), List.of());
        // 三个分支任一满足即可。Params 不解析 anyOf（它只管 required 与单字段规则），
        // 这里写进 schema 是为了让模型在 catalog 里就看见「不能什么都不给」，
        // 真正的兜底仍是下面 read() 里的显式检查。
        schema.put("anyOf", List.of(
                Map.of("required", List.of("sessionId")),
                Map.of("required", List.of("dateFrom")),
                Map.of("required", List.of("dateTo"))));
        return ToolSpec.builder("export_attendance", "L0", "导出考勤明细 Excel")
                .description("把指定场次或时间范围的考勤明细生成 .xlsx（缺勤行整行红底高亮），"
                             + "返回文件内容供界面下载。只读，不修改任何数据。"
                             + "至少要给 sessionId 或 dateFrom/dateTo 之一。")
                .params(schema)
                .returns(Map.of("type", "object"))
                .read((ctx, raw) -> {
                    Params p = Params.of(raw, schema);
                    Long sessionId = p.lng("sessionId");
                    Long classId = p.lng("classId");
                    Long courseId = p.lng("courseId");
                    LocalDate from = p.date("dateFrom");
                    LocalDate to = p.date("dateTo");

                    // 不给范围等于「导出全库」：教师点一次就可能生成几万行。
                    // 这类默认值必须显式拒绝，而不是替用户挑一个看起来合理的范围。
                    if (sessionId == null && from == null && to == null) {
                        throw new BizException(ErrorCode.NOT_FOUND,
                                "请至少给出场次（sessionId）或日期范围（dateFrom/dateTo）");
                    }
                    if (from != null && to != null && from.isAfter(to)) {
                        throw new BizException(ErrorCode.NOT_FOUND, "dateFrom 不能晚于 dateTo");
                    }
                    // 先按 principal 判一次：@DataScope 也会拒，但那里抛的是范围异常，
                    // 提示语里没有具体是哪个班，用户不知道该改哪。
                    if (classId != null) {
                        requireClass(ctx, classId);
                    }
                    if (courseId != null) {
                        requireCourse(ctx, courseId);
                    }

                    AttendanceQueryService.ExportFile file =
                            attendance.exportXlsxFile(sessionId, classId, courseId, from, to);
                    String base64 = Base64.getEncoder().encodeToString(file.bytes());
                    if (base64.length() > MAX_BASE64_CHARS) {
                        throw new BizException(ErrorCode.NOT_FOUND,
                                "导出 " + file.rows() + " 行超出单次上限，请缩小日期范围或指定班级后再导");
                    }

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("fileName", fileName(sessionId, from, to));
                    data.put("contentType", XLSX_MIME);
                    data.put("sizeBytes", file.bytes().length);
                    data.put("rowCount", file.rows());
                    data.put("contentBase64", base64);
                    return AgentDtos.ToolResult.of(data, file.rows());
                })
                .build();
    }

    private static void requireClass(ToolCtx ctx, long classId) {
        if (!ctx.principal().allowsClass(classId)) {
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED, "你当前的数据范围不包含该班级");
        }
    }

    private static void requireCourse(ToolCtx ctx, long courseId) {
        if (!ctx.principal().allowsCourse(courseId)) {
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED, "你当前的数据范围不包含该课程");
        }
    }

    static String fileName(Long sessionId, LocalDate from, LocalDate to) {
        if (sessionId != null) {
            return "考勤记录_场次" + sessionId + ".xlsx";
        }
        if (from != null && to != null) {
            return from.equals(to)
                    ? "考勤记录_" + from + ".xlsx"
                    : "考勤记录_" + from + "_" + to + ".xlsx";
        }
        if (from != null) {
            return "考勤记录_" + from + "起.xlsx";
        }
        if (to != null) {
            return "考勤记录_截至" + to + ".xlsx";
        }
        return "考勤记录.xlsx";
    }
}
