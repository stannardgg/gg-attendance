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
 * 教师自助：提交 / 撤回 / 查看自己的资料修改申请（CR-019）。
 *
 * <p>只有 TEACHER 能进来 —— ADMIN 没有 teacher 档案（teacher_id 为 null），
 * 让他走这条路只会拿到一条没有意义的申请；管理员改资料本来就有
 * {@code PUT /admin/teachers/{id}} 这个直通入口。
 */
@RestController
@RequestMapping("/api/v1/teacher/profile-requests")
@PreAuthorize("hasRole('TEACHER')")
public class TeacherProfileController {

    private final TeacherProfileRequestService service;

    public TeacherProfileController(TeacherProfileRequestService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<TeacherProfileRequestService.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(service.listMine(PageQuery.of(page, size)));
    }

    @PostMapping
    public Result<TeacherProfileRequestService.Resp> submit(
            @Valid @RequestBody TeacherProfileRequestService.SubmitReq req) {
        return Result.ok(service.submit(req));
    }

    @PostMapping("/{id}/withdraw")
    public Result<TeacherProfileRequestService.Resp> withdraw(@PathVariable long id) {
        return Result.ok(service.withdraw(id));
    }
}
