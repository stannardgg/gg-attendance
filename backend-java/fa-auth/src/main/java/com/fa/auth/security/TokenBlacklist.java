package com.fa.auth.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * JWT 黑名单。登出时把 jti 写进 Redis，TTL = 该令牌的剩余有效期（§9.3）。
 *
 * <p>为什么按 jti 而不是按 userId：用户可能同时在手机和电脑登录，
 * 按用户作废会把另一台设备也踢下线。
 *
 * <p><b>Redis 不可用时的取舍</b>：查询失败按「未拉黑」处理并打 error 日志。
 * 这等于登出在故障期间失效——但另一条路是让所有已登录用户全部 401，
 * 对课堂签到这种场景，可用性优先。真正高风险的操作（如改密）会用另一套机制
 * 把该用户的全部令牌作废，不依赖这里。
 */
@Component
public class TokenBlacklist {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklist.class);
    private static final String PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redis;

    public TokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void revoke(String jti, long remainingSeconds) {
        if (jti == null || remainingSeconds <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + jti, "1", Duration.ofSeconds(remainingSeconds));
        } catch (RedisConnectionFailureException e) {
            log.error("blacklist-write-failed jti={}", jti, e);
        }
    }

    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
        } catch (RedisConnectionFailureException e) {
            log.error("blacklist-read-failed jti={} —— 本次按未拉黑处理", jti, e);
            return false;
        }
    }
}
