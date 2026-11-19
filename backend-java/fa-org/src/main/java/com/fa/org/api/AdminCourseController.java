package com.fa.org.api;

import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import com.fa.org.api.dto.ClassDtos;
import com.fa.org.api.dto.CourseDtos;
import com.fa.org.app.CourseService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程管理端点（项目文档 §8.4.2）。
 *
 * <p>★ 这里的 DTO 没有 classId。请求体若带 classId，会被 Jackson 的
 * fail-on-unknown-properties 拒绝（400），这是场景 16 的契约级保证。
 */
@RestController
@RequestMapping("/api/v1/admin/courses")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminCourseController {

    private final CourseService courseService;

    public AdminCourseController(CourseService courseService) {
        this.courseService = courseService;
    }

    @GetMapping
    public Result<PageResult<CourseDtos.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String semester,
            @RequestParam(required = false) String status) {
        return Result.ok(courseService.list(PageQuery.of(page, size), keyword, semester, status));
    }

    @GetMapping("/{id}")
    public Result<CourseDtos.Resp> get(@PathVariable long id) {
        return Result.ok(courseService.get(id));
    }

    @PostMapping
    public Result<CourseDtos.Resp> create(@Valid @RequestBody CourseDtos.CreateReq req) {
        return Result.ok(courseService.create(req));
    }

    @PutMapping("/{id}")
    public Result<CourseDtos.Resp> update(@PathVariable long id,
                                          @Valid @RequestBody CourseDtos.UpdateReq req) {
        return Result.ok(courseService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<ClassDtos.DeleteResult> delete(@PathVariable long id) {
        return Result.ok(courseService.delete(id));
    }
}
