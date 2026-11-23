package com.fa.org.api;

import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import com.fa.org.api.dto.CourseDtos;
import com.fa.org.app.CourseGrantService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程授权学生端点（CR-026）。
 *
 * <p>挂在课程子资源 {@code /courses/{courseId}/grants} 下——授权跟着课程走，
 * 与 {@code /teacher-scopes}（挂独立资源）不同：授权名单是课程的一个视图，
 * 数量小、随课程生灭，不独立分页检索。
 *
 * <p>★ 本控制器依然不出现 classId 请求字段：学生的班级由后端读
 * {@code student.class_id} 现值决定（转班后授权自动跟随新班级校验），
 * 「选择班级」只是前端选择器的交互方式。
 */
@RestController
@RequestMapping("/api/v1/admin/courses/{courseId}/grants")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminCourseGrantController {

    private final CourseGrantService grantService;

    public AdminCourseGrantController(CourseGrantService grantService) {
        this.grantService = grantService;
    }

    @GetMapping
    public Result<PageResult<CourseDtos.GrantResp>> list(
            @PathVariable long courseId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(grantService.list(courseId, PageQuery.of(page, size)));
    }

    @PostMapping
    public Result<CourseDtos.GrantResp> grant(@PathVariable long courseId,
                                              @Valid @RequestBody CourseDtos.GrantReq req) {
        return Result.ok(grantService.grant(courseId, req));
    }

    @DeleteMapping("/{studentId}")
    public Result<Void> revoke(@PathVariable long courseId, @PathVariable long studentId) {
        grantService.revoke(courseId, studentId);
        return Result.ok();
    }
}
