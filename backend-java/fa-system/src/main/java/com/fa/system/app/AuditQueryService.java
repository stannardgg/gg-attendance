package com.fa.system.app;

import com.fa.common.api.PageQuery;
import com.fa.common.api.PageResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa.system.api.dto.SystemDtos;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * 审计查询（WP-08 验收项：可按操作人/动作/时间查询，仅 ADMIN）。
 *
 * <p>`actorName` 不落库，展示时按 role 关联 teacher / student 取姓名：
 * 审计表只存 actor_id 是有意的——改名之后历史日志不该跟着变，
 * 但界面上又必须给人看，所以在这里做一次 join。
 *
 * <p>client_ip 出库即脱敏到 /24（§13.4）：审计要能定位到网段，但不能变成定位到人。
 */
@Service
public class AuditQueryService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public PageResult<SystemDtos.AuditLog> list(PageQuery query, Long actorId, String action,
                                                String targetType, LocalDateTime from,
                                                LocalDateTime to) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (actorId != null) {
            where.append(" AND a.actor_id = ?");
            args.add(actorId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = ?");
            args.add(action.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append(" AND a.target_type = ?");
            args.add(targetType.trim());
        }
        if (from != null) {
            where.append(" AND a.created_at >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND a.created_at <= ?");
            args.add(to);
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log a" + where,
                Long.class, args.toArray());
        if (total == null || total == 0) {
            return PageResult.empty(query);
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add(query.offset());
        List<SystemDtos.AuditLog> items = jdbc.query("""
                SELECT a.id, a.actor_id, a.actor_role, a.action, a.target_type, a.target_id,
                       a.before_json, a.after_json, a.client_ip, a.trace_id, a.created_at,
                       COALESCE(t.name, st.name, ac.username) AS actor_name
                FROM audit_log a
                LEFT JOIN teacher t ON t.account_id = a.actor_id
                LEFT JOIN student st ON st.account_id = a.actor_id
                LEFT JOIN account ac ON ac.id = a.actor_id
                """ + where + " ORDER BY a.id DESC LIMIT ? OFFSET ?",
                mapper(), pageArgs.toArray());
        return PageResult.of(items, total, query);
    }

    private RowMapper<SystemDtos.AuditLog> mapper() {
        return (rs, i) -> new SystemDtos.AuditLog(
                rs.getLong("id"),
                nullableLong(rs, "actor_id"),
                rs.getString("actor_name"),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("target_type"),
                nullableLong(rs, "target_id"),
                readJson(rs, "before_json"),
                readJson(rs, "after_json"),
                maskIp(rs.getString("client_ip")),
                rs.getString("trace_id"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());
    }

    /**
     * 脱敏到 /24（IPv4）或 /64（IPv6 前缀）。
     *
     * <p>用纯字符串处理而不是 InetAddress：审计查询是高频只读接口，
     * 为了一条 IP 去触发 DNS 解析风险不值得（InetAddress 可能做反向解析）。
     */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        if (ip.contains(":")) {
            String[] parts = ip.split(":");
            return String.join(":", java.util.Arrays.copyOf(parts, Math.min(4, parts.length))) + "::/64";
        }
        int idx = ip.indexOf('.');
        if (idx < 0) {
            return ip;
        }
        return ip.substring(0, idx) + ".0.0.0/24";
    }

    private Map<String, Object> readJson(ResultSet rs, String col) throws SQLException {
        String raw = rs.getString(col);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, MAP);
        } catch (Exception e) {
            // 历史数据格式不对不能让整页 500：退回空对象，前端仍能看到这条日志存在
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_raw", raw);
            return fallback;
        }
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
