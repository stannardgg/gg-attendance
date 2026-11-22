package com.fa.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * JWT 签发与校验。HS256，密钥从环境变量注入（项目文档 §9.3）。
 *
 * <p>为什么不用 Spring Security 的 Nimbus JwtEncoder：这里只需要 HS256 + 自定义 claim，
 * jjwt 的 API 更直白，也省掉一层依赖。密钥长度不足 32 字节会直接启动失败——
 * 让它早失败，比上线后被人爆破强。
 *
 * <p>三类异常分得很细，是为了让调用方能区分「过期」（可以让前端去 refresh）与
 * 「伪造/篡改」（必须重新登录）。都映射成 {@code FA-1002} 但日志里要能区分。
 */
public class JwtUtil {

    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TEACHER_ID = "teacherId";
    public static final String CLAIM_STUDENT_ID = "studentId";
    public static final String CLAIM_TOKEN_TYPE = "typ";

    /**
     * 凭据版本（credential version）。账号改密或改登录名时库里 +1，
     * 旧令牌带的 cv 对不上，鉴权直接 401。
     *
     * <p>为什么不复用 Redis 黑名单：登出按 jti 撤销，是"这一张令牌不要了"；
     * 改密是"这个账号之前签发的全部令牌都不要了"。前者可能因为 Redis 故障
     * 静默失效，后者是安全底线，必须由数据库这个唯一事实源兜住。
     * 老令牌上没有这个 claim，按 0 处理 —— 与升级前的默认值一致，不会误杀。
     */
    public static final String CLAIM_CREDENTIAL_VERSION = "cv";

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final String ISSUER = "gg-attendance";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtUtil(String secret, Duration accessTtl, Duration refreshTtl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET 至少 " + MIN_SECRET_BYTES + " 字节，当前不满足。请通过环境变量注入。");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public String issueAccess(long accountId, String username, String role,
                              Long teacherId, Long studentId, int credentialVersion) {
        return issue(accountId, username, role, teacherId, studentId, credentialVersion,
                TYPE_ACCESS, accessTtl);
    }

    public String issueRefresh(long accountId, String username, String role,
                               Long teacherId, Long studentId, int credentialVersion) {
        return issue(accountId, username, role, teacherId, studentId, credentialVersion,
                TYPE_REFRESH, refreshTtl);
    }

    private String issue(long accountId, String username, String role, Long teacherId,
                         Long studentId, int credentialVersion, String type, Duration ttl) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(accountId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_CREDENTIAL_VERSION, credentialVersion)
                .claim(CLAIM_TOKEN_TYPE, type);
        if (teacherId != null) {
            builder.claim(CLAIM_TEACHER_ID, teacherId);
        }
        if (studentId != null) {
            builder.claim(CLAIM_STUDENT_ID, studentId);
        }
        return builder.signWith(key).compact();
    }

    /**
     * 解析并校验签名、过期时间、签发者。
     *
     * @throws io.jsonwebtoken.ExpiredJwtException       过期
     * @throws io.jsonwebtoken.security.SignatureException 签名不匹配（篡改或换过密钥）
     * @throws JwtException                              其他（格式错误、issuer 不符等）
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** 只用于判断「是不是过期」，不抛出。 */
    public boolean isExpired(String token) {
        try {
            parse(token);
            return false;
        } catch (ExpiredJwtException e) {
            return true;
        } catch (JwtException e) {
            return true;
        }
    }

    public long remainingSeconds(String token) {
        try {
            Claims claims = parse(token);
            long left = claims.getExpiration().toInstant().getEpochSecond() - Instant.now().getEpochSecond();
            return Math.max(left, 0);
        } catch (ExpiredJwtException e) {
            return 0;
        }
    }

    public TokenPayload toPayload(Claims claims) {
        Long teacherId = claims.get(CLAIM_TEACHER_ID) == null
                ? null : ((Number) claims.get(CLAIM_TEACHER_ID)).longValue();
        Long studentId = claims.get(CLAIM_STUDENT_ID) == null
                ? null : ((Number) claims.get(CLAIM_STUDENT_ID)).longValue();
        Number cv = claims.get(CLAIM_CREDENTIAL_VERSION, Number.class);
        return new TokenPayload(
                Long.parseLong(claims.getSubject()),
                claims.get(CLAIM_USERNAME, String.class),
                claims.get(CLAIM_ROLE, String.class),
                teacherId,
                studentId,
                cv == null ? 0 : cv.intValue(),
                claims.getId(),
                claims.get(CLAIM_TOKEN_TYPE, String.class));
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }
}
