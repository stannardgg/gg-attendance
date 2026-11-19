package com.fa.agent.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * Agent 留痕。
 *
 * <p>两张表，职责不同，不能合并：
 * <ul>
 *   <li>{@code agent_query_log}：只读调用。回答末尾的「命中 N 条」就来自这里，
 *       它同时是「这个数据是谁在什么时候问的」的证据。</li>
 *   <li>{@code agent_action_log}：写操作。多出 params_hash / token_fingerprint /
 *       before_json / after_json / undoable_until —— 撤销与追责全靠这几列。</li>
 * </ul>
 *
 * <p><b>令牌原文绝不入库</b>：只存 SHA-256 指纹。审计需要的是「这次操作对应哪次确认」，
 * 而不是「能把这个令牌再拿出来用一次」。
 */
@Component
public class AgentLogService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgentLogService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void writeQuery(long userId, String conversationId, String tool, Map<String, Object> params,
                           int resultCount, long durationMs, boolean ok, String errorCode,
                           String traceId) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO agent_query_log
                        (user_id, conversation_id, tool, params_json, result_count,
                         duration_ms, ok, error_code, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            ps.setLong(1, userId);
            ps.setString(2, conversationId);
            ps.setString(3, tool);
            ps.setString(4, json(params));
            ps.setInt(5, resultCount);
            ps.setInt(6, (int) durationMs);
            ps.setBoolean(7, ok);
            ps.setString(8, errorCode);
            ps.setString(9, traceId);
            return ps;
        });
    }

    public long writeAction(long userId, String conversationId, String tool,
                            Map<String, Object> params, String paramsHash, String tokenFingerprint,
                            Object before, Object after, int affectedCount,
                            boolean undoable, LocalDateTime undoableUntil, String traceId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO agent_action_log
                        (user_id, conversation_id, tool, params_json, params_hash, token_fingerprint,
                         before_json, after_json, affected_count, undoable, undoable_until, trace_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, conversationId);
            ps.setString(3, tool);
            ps.setString(4, json(params));
            ps.setString(5, paramsHash);
            ps.setString(6, tokenFingerprint);
            ps.setString(7, json(before));
            ps.setString(8, json(after));
            ps.setInt(9, affectedCount);
            ps.setBoolean(10, undoable);
            ps.setTimestamp(11, undoableUntil == null ? null : Timestamp.valueOf(undoableUntil));
            ps.setString(12, traceId);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("agent_action_log 未返回自增 id");
        }
        return key.longValue();
    }

    /** 撤销动作本身也记一条日志，并用 undone_log_id 指回它（契约 §internal.yaml 的要求）。 */
    public long writeUndoLog(long userId, String conversationId, String tool,
                             Map<String, Object> params, Object before, Object after, String traceId) {
        return writeAction(userId, conversationId, tool, params,
                "undo", "undo", before, after, 0, false, null, traceId);
    }

    public ActionLog findAction(long id) {
        List<ActionLog> rows = jdbc.query("""
                SELECT id, user_id, conversation_id, tool, params_json, params_hash,
                       token_fingerprint, before_json, after_json, affected_count,
                       undoable, undoable_until, undone_at, undone_by, undone_log_id, created_at
                FROM agent_action_log WHERE id = ?
                """, (rs, i) -> new ActionLog(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("conversation_id"),
                rs.getString("tool"),
                rs.getString("params_json"),
                rs.getString("params_hash"),
                rs.getString("token_fingerprint"),
                rs.getString("before_json"),
                rs.getString("after_json"),
                rs.getInt("affected_count"),
                rs.getBoolean("undoable"),
                toLocal(rs.getTimestamp("undoable_until")),
                toLocal(rs.getTimestamp("undone_at")),
                rs.getObject("undone_by") == null ? null : rs.getLong("undone_by"),
                rs.getObject("undone_log_id") == null ? null : rs.getLong("undone_log_id"),
                toLocal(rs.getTimestamp("created_at"))), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markUndone(long originalId, long undoneLogId, long undoneBy) {
        jdbc.update("""
                UPDATE agent_action_log
                SET undone_at = NOW(3), undone_by = ?, undone_log_id = ?
                WHERE id = ? AND undone_at IS NULL
                """, undoneBy, undoneLogId, originalId);
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("留痕序列化失败", e);
        }
    }

    public record ActionLog(long id, long userId, String conversationId, String tool,
                            String paramsJson, String paramsHash, String tokenFingerprint,
                            String beforeJson, String afterJson, int affectedCount,
                            boolean undoable, LocalDateTime undoableUntil,
                            LocalDateTime undoneAt, Long undoneBy, Long undoneLogId,
                            LocalDateTime createdAt) {
    }
}
