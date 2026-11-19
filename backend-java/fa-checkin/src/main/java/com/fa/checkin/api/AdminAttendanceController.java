package com.fa.checkin.api;

import com.fa.checkin.api.dto.AttendanceDtos;
import com.fa.checkin.app.AttendanceQueryService;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端考勤（契约 java-api.yaml admin-attendance 三端点）。
 *
 * <p>列表与大屏共用：大屏是「按 sessionId 过滤 + 轮询/WS 增量」，不再单独设计一个接口，
 * 否则两边的口径迟早会分叉。
 */
@RestController
@RequestMapping("/api/v1/admin/attendance")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminAttendanceController {

    private final AttendanceQueryService service;

    public AdminAttendanceController(AttendanceQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<AttendanceDtos.Row>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String studentKeyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return Result.ok(service.list(PageQuery.of(page, size), sessionId, classId, courseId,
                status, studentKeyword, dateFrom, dateTo));
    }

    @PatchMapping("/{id}")
    public Result<AttendanceDtos.Row> patch(@PathVariable long id,
                                            @RequestBody AttendanceDtos.PatchReq req) {
        return Result.ok(service.patch(id, req));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        byte[] xlsx = service.exportXlsx(sessionId, classId, courseId, dateFrom, dateTo);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"attendance.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
    }
}
