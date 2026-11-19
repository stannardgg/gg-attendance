package com.fa.auth.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录失败计数（§13.3：连续 5 次锁 10 分钟）。
 *
 * <p>计数粒度是「用户名」而不是「用户名+IP」：教室里同一个出口 IP 有几十个学生，
 * 按 IP 计数会把一个学生输错密码的代价转嫁给全班。
 *
 * <p>Redis 不可用时**不锁定**（放行），理由同 TokenBlacklist：可用性优先，
 * 且此时仍有 BCrypt 校验兜底。
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final String PREFIX = "login:fail:";
    private static final int MAX_FAILS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLocked(String username) {
        try {
            String v = redis.opsForValue().get(PREFIX + username);
            return v != null && Integer.parseInt(v) >= MAX_FAILS;
        } catch (RedisConnectionFailureException e) {
            log.error("login-limit-read-failed username={}", username, e);
            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void onFailure(String username) {
        try {
            String key = PREFIX + username;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW);
            }
        } catch (RedisConnectionFailureException e) {
            log.error("login-limit-write-failed username={}", username, e);
        }
    }

    public void onSuccess(String username) {
        try {
            redis.delete(PREFIX + username);
        } catch (RedisConnectionFailureException e) {
            log.error("login-limit-clear-failed username={}", username, e);
        }
    }

    static int maxFails() {
        return MAX_FAILS;
    }
}
