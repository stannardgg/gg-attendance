package com.fa.system.app;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 日志类数据的保留期兜底清理（CR-028，配合《数据处理说明》保留期限表）。
 *
 * <p>审计与 Agent 留痕是运营数据而非业务凭证，必须有限保留——只写期限不做清理
 * 等于没有期限。三张表都有 {@code created_at} 索引（idx_audit_created 等），
 * 单条范围 DELETE 即可，不需要分批。
 *
 * <p>期限口径（docs/compliance/data-processing-statement.md §保留期限）：
 * <ul>
 *   <li>{@code audit_log}：365 天（V1 DDL 注释口径「保留期 ≥1 年」，到期即清）；</li>
 *   <li>{@code agent_query_log} / {@code agent_action_log}：180 天。</li>
 * </ul>
 *
 * <p>与既有清理链路的关系：考勤业务记录（attendance/checkin_session）不在此清理
 * ——它们是学籍管理凭证，保留期由业主制度决定，本 Job 不碰；Redis 侧的采集样本
 * 与活体挑战靠 TTL 自灭（15 分钟 / challenge.ttl_sec）；容器 stdout 日志由 compose
 * {@code x-logging}（10m × 3）滚动；数据库备份由 {@code backup.sh} 的
 * {@code BACKUP_KEEP_DAYS}（默认 7 天）滚动。
 */
@Component
public class LogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final int auditDays;
    private final int agentDays;

    public LogRetentionJob(JdbcTemplate jdbc,
                           @Value("${app.retention.audit-log-days:365}") int auditDays,
                           @Value("${app.retention.agent-log-days:180}") int agentDays) {
        this.jdbc = jdbc;
        this.auditDays = auditDays;
        this.agentDays = agentDays;
    }

    /** 每天 03:40 执行（错开 SessionExpireJob 与备份窗口）。 */
    @Scheduled(cron = "${app.retention.cron:0 40 3 * * *}", zone = "Asia/Shanghai")
    public void purge() {
        LocalDateTime now = LocalDateTime.now();
        int audit = jdbc.update("delete from audit_log where created_at < ?", now.minusDays(auditDays));
        int queryLog = jdbc.update("delete from agent_query_log where created_at < ?", now.minusDays(agentDays));
        int actionLog = jdbc.update("delete from agent_action_log where created_at < ?", now.minusDays(agentDays));
        if (audit + queryLog + actionLog > 0) {
            log.info("retention purge audit_log={} agent_query_log={} agent_action_log={}",
                    audit, queryLog, actionLog);
        }
    }
}
