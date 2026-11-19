package com.fa.agent.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 确认令牌：60 秒、一次性、绑定 paramsHash（§11.5 第 4 道闸）。
 *
 * <p>三个必须成立的约束，缺一个这套机制就退化回「前端点一下」：
 * <ol>
 *   <li><b>服务端签发</b>：令牌是 32 字节随机数，Agent 侧无法构造或猜测；</li>
 *   <li><b>一次性</b>：校验时用 {@code GETDEL} 原子取出并删除，重放必然失败；</li>
 *   <li><b>绑定参数哈希</b>：确认时重算 params 的规范化哈希，与签发时不一致即 FA-6003。</li>
 * </ol>
 *
 * <p>为什么令牌原文不落库：{@code agent_action_log.token_fingerprint} 存的是 SHA-256，
 * 库被读走也无法反推出可用的令牌——审计要的是「能对上号」，不是「能复现」。
 */
@Component
public class ConfirmTokenService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmTokenService.class);
    private static final String PREFIX = "agent:confirm:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public ConfirmTokenService(StringRedisTemplate redis, ObjectMapper objectMapper,
                               @Value("${app.agent.confirm-ttl-seconds:60}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** 签发结果：令牌原文 + 到期时间（给界面做倒计时）。 */
    public record Issued(String token, LocalDateTime expiresAt, String paramsHash) {
    }

    public Issued issue(long accountId, String tool, Map<String, Object> params) {
        String token = randomToken();
        String hash = paramsHash(params);
        Payload payload = new Payload(accountId, tool, hash, LocalDateTime.now().plus(ttl).toString());
        try {
            redis.opsForValue().set(PREFIX + token, objectMapper.writeValueAsString(payload), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("确认令牌序列化失败", e);
        } catch (RuntimeException e) {
            // Redis 不可用时必须失败，不能「发一个校验时永远查不到的令牌」——
            // 那会让用户点了确认却永远 FA-6002，比直接报错更难排查。
            log.error("confirm-token-issue-failed tool={}", tool, e);
            throw e;
        }
        return new Issued(token, LocalDateTime.now().plus(ttl), hash);
    }

    /**
     * 校验并消费令牌。
     *
     * <p>令牌在取出的瞬间就被删除（GETDEL），所以<b>即使后面参数校验失败，令牌也已作废</b>，
     * 必须重新发起预览。这是刻意的：否则攻击者可以拿一个令牌反复试探不同参数。
     *
     * @return 令牌里绑定的 paramsHash
     * @throws com.fa.common.error.BizException FA-6002 不存在/已用过/已过期；FA-6003 工具或参数不符
     */
    public String consume(long accountId, String tool, String token, Map<String, Object> params) {
        if (token == null || token.isBlank()) {
            throw new com.fa.common.error.BizException(com.fa.common.error.ErrorCode.CONFIRM_EXPIRED);
        }
        String key = PREFIX + token.trim();
        String raw;
        try {
            raw = redis.opsForValue().getAndDelete(key);
        } catch (RuntimeException e) {
            log.error("confirm-token-consume-failed", e);
            throw new com.fa.common.error.BizException(
                    com.fa.common.error.ErrorCode.INTERNAL_ERROR, "确认令牌校验暂不可用，请重试");
        }
        if (raw == null) {
            // 三种情况归一：过期、已用过、伪造。都不告诉调用方是哪一种。
            throw new com.fa.common.error.BizException(com.fa.common.error.ErrorCode.CONFIRM_EXPIRED);
        }
        Payload payload;
        try {
            payload = objectMapper.readValue(raw, Payload.class);
        } catch (JsonProcessingException e) {
            throw new com.fa.common.error.BizException(com.fa.common.error.ErrorCode.CONFIRM_EXPIRED);
        }
        if (payload.accountId() != accountId) {
            throw new com.fa.common.error.BizException(
                    com.fa.common.error.ErrorCode.AGENT_SCOPE_DENIED, "该令牌不是签发给当前用户的");
        }
        String actual = paramsHash(params);
        if (!payload.tool().equals(tool) || !payload.paramsHash().equals(actual)) {
            throw new com.fa.common.error.BizException(com.fa.common.error.ErrorCode.PARAMS_CHANGED);
        }
        return payload.paramsHash();
    }

    /** 审计用指纹：只存哈希，不存原文。 */
    public static String fingerprint(String token) {
        return sha256(token == null ? "" : token);
    }

    /**
     * 参数规范化哈希。
     *
     * <p>必须排序后序列化：JSON 对象本身无序，{@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}}
     * 语义相同，若不排序会把「原样重放」误判成「参数被篡改」。
     */
    public String paramsHash(Map<String, Object> params) {
        try {
            return sha256(objectMapper.writeValueAsString(canonical(params)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("参数无法规范化", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonical(Object node) {
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v)));
            return sorted;
        }
        if (node instanceof java.util.List<?> list) {
            return list.stream().map(this::canonical).toList();
        }
        return node;
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Payload(long accountId, String tool, String paramsHash, String expiresAt) {
    }
}
