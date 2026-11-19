package com.fa.system.api;

import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fa.common.api.Result;
import com.fa.system.api.dto.SystemDtos;
import com.fa.system.app.AuditQueryService;
import com.fa.system.app.ConfigService;
import com.fa.system.app.StatsService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * admin-system：概览 / 审计 / 配置（契约 java-api.yaml）。
 *
 * <p>为什么这三块放一个模块：它们都是「只读为主、跨业务域」的聚合视图，
 * 放到 fa-org 或 fa-checkin 里会让那两个模块反向依赖彼此的统计口径。
 *
 * <p>权限：概览对 TEACHER 开放（只看授权范围）；审计与配置仅 ADMIN。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminSystemController {

    private final StatsService stats;
    private final AuditQueryService audits;
    private final ConfigService configs;

    public AdminSystemController(StatsService stats, AuditQueryService audits, ConfigService configs) {
        this.stats = stats;
        this.audits = audits;
        this.configs = configs;
    }

    @GetMapping("/stats/overview")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public Result<SystemDtos.Overview> overview(
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return Result.ok(stats.overview(classId, courseId, dateFrom, dateTo));
    }

    @GetMapping("/audit-logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageResult<SystemDtos.AuditLog>> auditLogs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dateTo) {
        return Result.ok(audits.list(PageQuery.of(page, size), actorId, action, targetType,
                dateFrom, dateTo));
    }

    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<SystemDtos.ConfigItem>> config(
            @RequestParam(required = false) String category) {
        return Result.ok(configs.list(category));
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<SystemDtos.ConfigItem>> updateConfig(
            @RequestBody SystemDtos.ConfigUpdateReq req) {
        return Result.ok(configs.update(req));
    }
}
