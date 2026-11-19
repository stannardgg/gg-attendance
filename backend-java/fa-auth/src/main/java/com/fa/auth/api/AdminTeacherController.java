package com.fa.auth.api;

import com.fa.auth.api.dto.TeacherDtos;
import com.fa.auth.app.TeacherService;
import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
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
 * 教师管理端点。**仅 ADMIN**——这是 §3.1 里唯一由管理员独占的业务 CRUD。
 *
 * <p>TEACHER 访问任一方法都应得到 403 FA-1003，这是 B2 的验收项之一
 * （场景 11：TEACHER 直接调 DELETE /admin/teachers/1）。
 */
@RestController
@RequestMapping("/api/v1/admin/teachers")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTeacherController {

    private final TeacherService teacherService;

    public AdminTeacherController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping
    public Result<PageResult<TeacherDtos.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword) {
        return Result.ok(teacherService.list(PageQuery.of(page, size), keyword));
    }

    @PostMapping
    public Result<TeacherDtos.Resp> create(@Valid @RequestBody TeacherDtos.CreateReq req) {
        return Result.ok(teacherService.create(req));
    }

    @PutMapping("/{id}")
    public Result<TeacherDtos.Resp> update(@PathVariable long id,
                                           @Valid @RequestBody TeacherDtos.UpdateReq req) {
        return Result.ok(teacherService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        teacherService.delete(id);
        return Result.ok();
    }
}
