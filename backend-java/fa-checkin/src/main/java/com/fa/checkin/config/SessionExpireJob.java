package com.fa.checkin.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.checkin.app.SessionService;
import com.fa.checkin.domain.CheckinSession;
import com.fa.checkin.infra.SessionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期扫描：每 30 秒把 end_at 已过的 ACTIVE 会话关掉（补 ABSENT）。
 *
 * <p>为什么必须有它：教师下课后不会记得点「结束签到」。没有兜底的话，
 * 会话永远 ACTIVE，学生第二天还能扫昨天的码（token 仍按时间片算得出来）。
 *
 * <p>多实例部署时这里会并发执行——close 本身幂等（已 CLOSED 直接返回），
 * 补 ABSENT 用 NOT EXISTS 保证只插一次，所以不需要分布式锁。
 */
@Component
public class SessionExpireJob {

    private static final Logger log = LoggerFactory.getLogger(SessionExpireJob.class);

    private final SessionMapper sessionMapper;
    private final SessionService sessionService;

    public SessionExpireJob(SessionMapper sessionMapper, SessionService sessionService) {
        this.sessionMapper = sessionMapper;
        this.sessionService = sessionService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 20_000)
    public void closeExpired() {
        List<CheckinSession> expired = sessionMapper.selectList(
                Wrappers.<CheckinSession>lambdaQuery()
                        .eq(CheckinSession::getStatus, "ACTIVE")
                        .lt(CheckinSession::getEndAt, LocalDateTime.now())
                        .last("limit 50"));
        for (CheckinSession s : expired) {
            try {
                sessionService.close(s.getId());
                log.info("auto-closed expired session {}", s.getId());
            } catch (RuntimeException e) {
                log.error("auto-close failed sessionId={}", s.getId(), e);
            }
        }
    }
}
