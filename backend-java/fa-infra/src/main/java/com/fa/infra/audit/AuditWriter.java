package com.fa.infra.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.SecurityUtils;
import com.fa.common.web.TraceContext;
import java.util.Map;
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
 *
 * <p><b>两个写入入口</b>：{@link #write(String, String, Long, String)} 只有一句
 * detail，够用于"发生了什么"；{@link #write(String, String, Long, Map, Map, String)}
 * 额外落 before/after 摘要，用于申诉处理这类"必须能看出改前改后"的场景
 * （16.3 场景 10 的验收点）。摘要里禁止出现人脸向量、相似度原值（§13.4）。
 */
@Component
public class AuditWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void write(String action, String targetType, Long targetId, String detail) {
        insert(action, targetType, targetId, null, null, detail);
    }

    /** 带变更前后摘要。before/after 传 null 表示该侧不适用。 */
    public void write(String action, String targetType, Long targetId,
                      Map<String, Object> before, Map<String, Object> after, String detail) {
        insert(action, targetType, targetId, toJson(before), toJson(after), detail);
    }

    private void insert(String action, String targetType, Long targetId,
                        String beforeJson, String afterJson, String detail) {
        CurrentUser user = SecurityUtils.currentOrNull();
        jdbc.update("""
                INSERT INTO audit_log (actor_id, actor_role, action, target_type, target_id,
                                       before_json, after_json, detail, trace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user == null ? null : user.accountId(),
                user == null ? "SYSTEM" : user.role(),
                action,
                targetType,
                targetId,
                beforeJson,
                afterJson,
                detail,
                TraceContext.currentTraceId());
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // 审计缺一列不能把业务打挂：降级为一句说明，至少留下"打算记什么"
            return "{\"unserializable\":\"" + value.keySet() + "\"}";
        }
    }
}
