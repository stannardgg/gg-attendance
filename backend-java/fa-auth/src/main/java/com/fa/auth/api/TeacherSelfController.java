package com.fa.auth.api;

import com.fa.auth.api.dto.TeacherDtos;
import com.fa.auth.app.TeacherService;
import com.fa.common.api.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师自助查看自己的档案（CR-019）。只读，且只能看自己：
 * 教师 id 从令牌里取，不接受请求参数，避免变成"按 id 查任意教师"的越权口子。
 */
@RestController
@RequestMapping("/api/v1/teacher/profile")
@PreAuthorize("hasRole('TEACHER')")
public class TeacherSelfController {

    private final TeacherService teacherService;

    public TeacherSelfController(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @GetMapping
    public Result<TeacherDtos.Resp> profile() {
        return Result.ok(teacherService.self());
    }
}
