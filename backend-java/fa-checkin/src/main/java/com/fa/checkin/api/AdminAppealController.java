package com.fa.checkin.api;

import com.fa.checkin.app.AppealService;
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

@RestController
@RequestMapping("/api/v1/admin/appeals")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class AdminAppealController {

    private final AppealService appealService;

    public AdminAppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @GetMapping
    public Result<PageResult<AppealService.Resp>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sessionId) {
        return Result.ok(appealService.list(PageQuery.of(page, size), status, sessionId));
    }

    @PostMapping("/{id}/handle")
    public Result<AppealService.Resp> handle(@PathVariable long id,
                                             @Valid @RequestBody AppealService.HandleReq req) {
        return Result.ok(appealService.handle(id, req));
    }
}
