package com.fa.org.api;

import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import com.fa.org.api.dto.ClassDtos;
import com.fa.org.api.dto.ScopeDtos;
import com.fa.org.api.dto.StudentDtos;
import com.fa.org.app.StudentService;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 学生管理端点（项目文档 §9.1 fa-org 的学生部分）。
 *
 * <p>CSV 导入用 multipart：字段名 {@code file} 与 {@code classCode}，
 * 与 OpenAPI 的 ImportStudents 一致。
 */
@RestController
@RequestMapping("/api/v1/admin/students")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminStudentController {

    private final StudentService studentService;

    public AdminStudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @GetMapping
    public Result<PageResult<StudentDtos.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return Result.ok(studentService.list(PageQuery.of(page, size), classId, keyword, status));
    }

    @PostMapping
    public Result<StudentDtos.Resp> create(@Valid @RequestBody StudentDtos.CreateReq req) {
        return Result.ok(studentService.create(req));
    }

    @PutMapping("/{id}")
    public Result<StudentDtos.Resp> update(@PathVariable long id,
                                           @Valid @RequestBody StudentDtos.UpdateReq req) {
        return Result.ok(studentService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<ClassDtos.DeleteResult> delete(@PathVariable long id) {
        return Result.ok(studentService.delete(id));
    }

    @PostMapping("/import")
    public Result<StudentDtos.ImportResult> importCsv(
            @RequestPart("file") MultipartFile file,
            @RequestParam("classCode") String classCode) throws IOException {
        return Result.ok(studentService.importCsv(file.getInputStream(), classCode));
    }

    @PostMapping("/{id}/face/review")
    public Result<ScopeDtos.FaceStatusResp> reviewFace(
            @PathVariable long id,
            @Valid @RequestBody ScopeDtos.FaceReviewReq req) {
        return Result.ok(studentService.reviewFace(id, req));
    }
}
