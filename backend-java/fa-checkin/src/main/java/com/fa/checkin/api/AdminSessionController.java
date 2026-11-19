package com.fa.checkin.api;

import com.fa.checkin.api.dto.CheckinDtos;
import com.fa.checkin.app.SessionService;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理端点（§8.4 createSession / closeSession / rotateQr / getQrCode）。
 *
 * <p>TEACHER 与 ADMIN 都能进，但 TEACHER 只能操作自己授权范围内的班级——
 * 过滤在 Service 层由 {@code @DataScope} 完成。
 */
@RestController
@RequestMapping("/api/v1/admin/sessions")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminSessionController {

    private final SessionService sessionService;

    public AdminSessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public Result<PageResult<CheckinDtos.Session>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {
        return Result.ok(sessionService.list(PageQuery.of(page, size),
                classId, courseId, status, dateFrom, dateTo));
    }

    @PostMapping
    public Result<CheckinDtos.Session> create(@Valid @RequestBody CheckinDtos.CreateReq req) {
        return Result.ok(sessionService.create(req));
    }

    @GetMapping("/{id}")
    public Result<CheckinDtos.Session> get(@PathVariable long id) {
        return Result.ok(sessionService.toRespPublic(id));
    }

    @PostMapping("/{id}/close")
    public Result<CheckinDtos.Session> close(@PathVariable long id) {
        return Result.ok(sessionService.close(id));
    }

    @PostMapping("/{id}/rotate-qr")
    public Result<CheckinDtos.QrData> rotateQr(@PathVariable long id) {
        return Result.ok(sessionService.rotateQr(id));
    }

    @GetMapping("/{id}/qrcode")
    public Result<CheckinDtos.QrData> qrcode(@PathVariable long id) {
        return Result.ok(sessionService.qrCode(id));
    }
}
