package com.fa.auth.api;

import com.fa.auth.app.StudentProfileRequestService;
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
 * 学生自助：提交 / 撤回 / 查看自己的密码修改申请（CR-024）。
 *
 * <p>只有 STUDENT 能进来——教师/管理员没有 student 档案（student_id 为 null），
 * 进来只会拿到 FORBIDDEN；教师改密走 {@code /teacher/profile-requests}，
 * 管理员改密本来就有 {@code PUT /admin/teachers/{id}} 与学生编辑入口。
 */
@RestController
@RequestMapping("/api/v1/student/profile-requests")
@PreAuthorize("hasRole('STUDENT')")
public class StudentProfileController {

    private final StudentProfileRequestService service;

    public StudentProfileController(StudentProfileRequestService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageResult<StudentProfileRequestService.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return Result.ok(service.listMine(PageQuery.of(page, size)));
    }

    @PostMapping
    public Result<StudentProfileRequestService.Resp> submit(
            @Valid @RequestBody StudentProfileRequestService.SubmitReq req) {
        return Result.ok(service.submit(req));
    }

    @PostMapping("/{id}/withdraw")
    public Result<StudentProfileRequestService.Resp> withdraw(@PathVariable long id) {
        return Result.ok(service.withdraw(id));
    }
}
