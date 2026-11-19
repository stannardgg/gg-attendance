package com.fa.checkin.api;

import com.fa.checkin.app.AppealService;
import com.fa.common.api.Result;
import com.fa.common.security.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mobile/appeals")
@PreAuthorize("hasRole('STUDENT')")
public class MobileAppealController {

    private final AppealService appealService;

    public MobileAppealController(AppealService appealService) {
        this.appealService = appealService;
    }

    @PostMapping
    public Result<AppealService.Resp> create(@Valid @RequestBody AppealService.CreateReq req) {
        return Result.ok(appealService.create(SecurityUtils.current().studentId(), req));
    }

    @PostMapping("/{id}/withdraw")
    public Result<AppealService.Resp> withdraw(@PathVariable long id) {
        return Result.ok(appealService.withdraw(id, SecurityUtils.current().studentId()));
    }
}
