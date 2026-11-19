package com.fa.org.api;

import com.fa.common.api.Result;
import com.fa.org.api.dto.ScopeDtos;
import com.fa.org.app.ScopeService;
import jakarta.validation.Valid;
import java.util.List;
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
 * 教师授权范围端点。**仅 ADMIN**（项目文档 §3.1 把这条列为管理员独占能力）。
 */
@RestController
@RequestMapping("/api/v1/admin/teacher-scopes")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTeacherScopeController {

    private final ScopeService scopeService;

    public AdminTeacherScopeController(ScopeService scopeService) {
        this.scopeService = scopeService;
    }

    @GetMapping
    public Result<List<ScopeDtos.Resp>> list(@RequestParam(required = false) Long teacherId) {
        return Result.ok(scopeService.list(teacherId));
    }

    @PostMapping
    public Result<ScopeDtos.Resp> grant(@Valid @RequestBody ScopeDtos.CreateReq req) {
        return Result.ok(scopeService.grant(req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable long id) {
        scopeService.revoke(id);
        return Result.ok();
    }
}
