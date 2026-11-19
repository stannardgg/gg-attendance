package com.fa.org.api;

import com.fa.common.api.Result;
import com.fa.common.security.SecurityUtils;
import com.fa.org.api.dto.ScopeDtos;
import com.fa.org.app.FaceEnrollService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学生本人的人脸采集（契约 {@code /api/v1/mobile/face/**}）。
 *
 * <p>只看 {@code SecurityUtils.current().studentId()}，不接受任何"帮别人提交"的参数——
 * 人脸是强身份数据，越权写入的后果是别人可以拿你的脸签到。
 * 教师审核走 {@code /api/v1/admin/students/{id}/face/review}，与这里职责分开。
 */
@RestController
@RequestMapping("/api/v1/mobile")
@PreAuthorize("hasRole('STUDENT')")
public class MobileFaceController {

    private final FaceEnrollService service;

    public MobileFaceController(FaceEnrollService service) {
        this.service = service;
    }

    @GetMapping("/face/status")
    public Result<ScopeDtos.FaceStatusResp> status() {
        return Result.ok(service.status(SecurityUtils.current().studentId()));
    }

    @PostMapping("/face/enroll")
    public Result<ScopeDtos.FaceStatusResp> enroll(@Valid @RequestBody ScopeDtos.FaceEnrollReq req) {
        return Result.ok(service.enroll(SecurityUtils.current().studentId(), req));
    }
}
