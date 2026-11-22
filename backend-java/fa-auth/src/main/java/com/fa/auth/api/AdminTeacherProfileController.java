package com.fa.auth.api;

import com.fa.auth.app.TeacherProfileRequestService;
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
 * 教师资料修改申请的审核端（仅 ADMIN）。
 *
 * <p>与 {@link TeacherProfileController} 分两个 Controller 而不是共用一个：
 * 自助路径与审核路径的鉴权是相反的（TEACHER vs ADMIN），
 * 分开写能让 {@code @PreAuthorize} 落在类上，不留"某个方法忘了加注解"的口子。
 */
@RestController
@RequestMapping("/api/v1/admin/teacher-profile-requests")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTeacherProfileController {

    private final TeacherProfileRequestService service;

    public AdminTeacherProfileController(TeacherProfileRequestService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<TeacherProfileRequestService.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Result.ok(service.list(PageQuery.of(page, size), status, keyword));
    }

    @PostMapping("/{id}/handle")
    public Result<TeacherProfileRequestService.Resp> handle(
            @PathVariable long id,
            @Valid @RequestBody TeacherProfileRequestService.HandleReq req) {
        return Result.ok(service.handle(id, req));
    }
}
