package com.fa.checkin.app;

import com.fa.checkin.api.dto.CheckinDtos;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 刷脸失败计数（CR-023）：每会话每人一个计数器，达到上限后不再放行签到。
 *
 * <p>为什么落 MySQL 而不是 Redis：这是**门禁**而不是缓存。项目在改密失效上
 * 已经拍过板——不用 Redis 黑名单，因为它故障时按「未拉黑」放行，改密不能有
 * 这种降级；失败计数同理，Redis 闪断把计数丢了，等于给刷脸攻击多送 3 次机会。
 * 一行 upsert，写入压力可忽略（只在 finish 判失败时发生，成功不写）。
 *
 * <p>谁写谁读：
 * <ul>
 *   <li>写：Python finish 判失败（rejectGate != commit）后经
 *       {@code POST /internal/checkin/attempt/fail} 上报——单一写入口原则不变；
 *   <li>读：{@code join}（≥ 上限 → FA-3005）、{@code sessions/active}
 *       （回填 retryLeft，首页据此拒绝点击）、Python {@code start}
 *       （旧票据在达到上限后不得再开挑战）。
 * </ul>
 *
 * <p>上限取 {@code app.checkin.max-retry}（默认 3，对应项目文档 §4.4.3 与
 * FA-3005 的 note）。sys_config 的 CHECKIN_MAX_RETRY 行已由 V7 修正为 3 供
 * 管理端查看；与 PASS_THRESHOLD 同一立场：fa-checkin 接配置中心前先吃环境变量。
 */
@Service
public class CheckinAttemptService {

    private final JdbcTemplate jdbc;

    private final int maxRetry;

    public CheckinAttemptService(JdbcTemplate jdbc,
                                 @Value("${app.checkin.max-retry:3}") int maxRetry) {
        this.jdbc = jdbc;
        this.maxRetry = maxRetry;
    }

    public int maxRetry() {
        return maxRetry;
    }

    /**
     * 记一次识别失败。rejectGate 仅作记录（排障时回答「三次都卡在哪道门」），
     * 不参与判定——无论卡在哪道门，消耗的都是同一次机会。
     *
     * <p>REQUIRES_NEW：finish 上报发生在 Python 侧的一次独立 HTTP 调用里，
     * 这里本来就没有外层事务；显式声明是防止未来有人把 controller 包进事务后
     * 上报失败回滚连带签到主流程。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CheckinDtos.AttemptData recordFailure(long sessionId, long studentId, String gate) {
        try {
            jdbc.update("""
                    INSERT INTO checkin_attempt (session_id, student_id, fail_count, last_gate, last_fail_at)
                    VALUES (?, ?, 1, ?, NOW(3))
                    ON DUPLICATE KEY UPDATE fail_count = fail_count + 1,
                        last_gate = VALUES(last_gate), last_fail_at = NOW(3)
                    """, sessionId, studentId, gate);
        } catch (DuplicateKeyException e) {
            // 并发两次上报：upsert 理论上不会撞唯一键，撞了说明走了极窄的竞态窗口。
            // 失败次数宁多不少（往严的方向降级），回读真实值。
        }
        return status(sessionId, studentId);
    }

    /** 当前计数状态。无记录 = 一次都没失败过。 */
    public CheckinDtos.AttemptData status(long sessionId, long studentId) {
        Integer count = jdbc.query("""
                SELECT fail_count FROM checkin_attempt
                 WHERE session_id = ? AND student_id = ?
                """, rs -> rs.next() ? rs.getInt(1) : 0, sessionId, studentId);
        int failCount = count == null ? 0 : count;
        int retryLeft = Math.max(0, maxRetry - failCount);
        return new CheckinDtos.AttemptData(sessionId, failCount, retryLeft, retryLeft == 0);
    }
}
