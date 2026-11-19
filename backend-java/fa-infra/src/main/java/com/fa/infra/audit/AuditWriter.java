package com.fa.infra.audit;

import com.fa.common.security.CurrentUser;
import com.fa.common.security.SecurityUtils;
import com.fa.common.web.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 最小可用的审计写入器。
 *
 * <p>为什么在 B2 就写：项目文档 §5.3.1 明确「转班 = 改 class_id + 留审计」，
 * 而完整的审计切面（{@code @AuditLog}）属于 WP-08、排在 B4。
 * 如果等到 B4 再补，B2 期间的转班、删除、归档都会没有痕迹，
 * 而这些恰恰是最需要追溯的操作。
 *
 * <p>所以这里先给一个直白的 insert，不做切面、不做 diff 计算——
 * 那部分等 WP-08 统一接管时再替换实现。{@code audit_log} 表结构已由 B1 冻结，不会被推翻。
 */
@Component
public class AuditWriter {

    private final JdbcTemplate jdbc;

    public AuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String action, String targetType, Long targetId, String detail) {
        CurrentUser user = SecurityUtils.currentOrNull();
        jdbc.update("""
                INSERT INTO audit_log (actor_id, actor_role, action, target_type, target_id, detail, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                user == null ? null : user.accountId(),
                user == null ? "SYSTEM" : user.role(),
                action,
                targetType,
                targetId,
                detail,
                TraceContext.currentTraceId());
    }
}
