package com.fa.system.app;

import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.SecurityUtils;
import com.fa.infra.audit.AuditWriter;
import com.fa.system.api.dto.SystemDtos;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统配置读写。
 *
 * <p>`editable = 0` 的项由标定脚本写入（例如人脸阈值），管理端只能看不能改：
 * 允许界面改它，等于让一个手滑把 FAR/FRR 标定结果覆盖掉，而且没人知道改过。
 * 所以这里必须拒绝，而不是「改了但记个审计」。
 *
 * <p>改完要写审计：配置是所有下游行为的输入，事后追查"为什么昨天开始全拒绝"
 * 时，第一条要看的就是这个。
 */
@Service
public class ConfigService {

    private final JdbcTemplate jdbc;
    private final AuditWriter auditWriter;

    public ConfigService(JdbcTemplate jdbc, AuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

    public List<SystemDtos.ConfigItem> list(String category) {
        StringBuilder sql = new StringBuilder("""
                SELECT config_key, config_value, value_type, category, description, editable, updated_at
                FROM sys_config
                """);
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" WHERE category = ?");
            args.add(category.trim().toUpperCase());
        }
        sql.append(" ORDER BY category, config_key");
        return jdbc.query(sql.toString(), mapper(), args.toArray());
    }

    @Transactional
    public List<SystemDtos.ConfigItem> update(SystemDtos.ConfigUpdateReq req) {
        if (req == null || req.items() == null || req.items().isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "没有要修改的配置项");
        }
        Long actorId = SecurityUtils.currentOrNull() == null
                ? null : SecurityUtils.current().accountId();

        for (SystemDtos.ConfigUpdateReq.Item item : req.items()) {
            if (item.configKey() == null || item.configKey().isBlank()) {
                throw new BizException(ErrorCode.NOT_FOUND, "配置键不能为空");
            }
            List<Boolean> editable = jdbc.query(
                    "SELECT editable FROM sys_config WHERE config_key = ?",
                    (rs, i) -> rs.getBoolean(1), item.configKey());
            if (editable.isEmpty()) {
                throw new BizException(ErrorCode.NOT_FOUND, "未知的配置项：" + item.configKey());
            }
            if (!editable.get(0)) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "配置项 " + item.configKey() + " 由标定脚本维护，管理端只读");
            }
            int n = jdbc.update("""
                    UPDATE sys_config SET config_value = ?, updated_by = ? WHERE config_key = ?
                    """, item.configValue(), actorId, item.configKey());
            if (n == 0) {
                throw new BizException(ErrorCode.NOT_FOUND, "未知的配置项：" + item.configKey());
            }
            auditWriter.write("CONFIG_UPDATE", "CONFIG", null,
                    item.configKey() + " → " + item.configValue());
        }
        return list(null);
    }

    private RowMapper<SystemDtos.ConfigItem> mapper() {
        return (rs, i) -> new SystemDtos.ConfigItem(
                rs.getString("config_key"),
                rs.getString("config_value"),
                rs.getString("value_type"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getBoolean("editable"),
                rs.getTimestamp("updated_at") == null
                        ? null : rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
