package com.fa.org.api;

import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import com.fa.org.api.dto.ClassDtos;
import com.fa.org.app.ClassService;
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
 * 班级管理端点（项目文档 §8.4.1）。
 *
 * <p>权限：TEACHER 与 ADMIN 都可进入，但 TEACHER 只能看到被授权的班级——
 * 过滤在 Service 层由 {@code @DataScope} 完成，这里只做角色拦截。
 * 前端隐藏按钮不是安全边界（§8.3）。
 */
@RestController
@RequestMapping("/api/v1/admin/classes")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminClassController {

    private final ClassService classService;

    public AdminClassController(ClassService classService) {
        this.classService = classService;
    }

    @GetMapping
    public Result<PageResult<ClassDtos.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String status) {
        return Result.ok(classService.list(PageQuery.of(page, size), keyword, grade, status));
    }

    @GetMapping("/{id}")
    public Result<ClassDtos.Resp> get(@PathVariable long id) {
        return Result.ok(classService.get(id));
    }

    @PostMapping
    public Result<ClassDtos.Resp> create(@Valid @RequestBody ClassDtos.CreateReq req) {
        return Result.ok(classService.create(req));
    }

    @PutMapping("/{id}")
    public Result<ClassDtos.Resp> update(@PathVariable long id,
                                         @Valid @RequestBody ClassDtos.UpdateReq req) {
        return Result.ok(classService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<ClassDtos.DeleteResult> delete(@PathVariable long id) {
        return Result.ok(classService.delete(id));
    }
}
