package com.fa.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 边界测试（项目文档 WP-02 验收：过期、篡改签名、错误 issuer）。
 *
 * <p>这三条不是形式主义——它们是「令牌体系是否可信」的全部依据。
 * 少测一条，就可能出现「改了 payload 还能用」或者「过期了还能用」。
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghij";

    private JwtUtil util(long accessTtlSeconds) {
        return new JwtUtil(SECRET, Duration.ofSeconds(accessTtlSeconds), Duration.ofDays(7));
    }

    @Test
    @DisplayName("签发的 access 能被解析，字段完整")
    void issueAndParse() {
        JwtUtil jwt = util(1800);
        String token = jwt.issueAccess(7L, "S2024001", "STUDENT", null, 3L);

        Claims claims = jwt.parse(token);
        TokenPayload payload = jwt.toPayload(claims);

        assertThat(payload.accountId()).isEqualTo(7L);
        assertThat(payload.username()).isEqualTo("S2024001");
        assertThat(payload.role()).isEqualTo("STUDENT");
        assertThat(payload.studentId()).isEqualTo(3L);
        assertThat(payload.teacherId()).isNull();
        assertThat(payload.tokenType()).isEqualTo(JwtUtil.TYPE_ACCESS);
        assertThat(payload.jti()).isNotBlank();
    }

    @Test
    @DisplayName("过期令牌抛 ExpiredJwtException，且 isExpired 返回 true")
    void expired() {
        JwtUtil jwt = util(-10);
        String token = jwt.issueAccess(1L, "admin", "ADMIN", null, null);

        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(ExpiredJwtException.class);
        assertThat(jwt.isExpired(token)).isTrue();
        assertThat(jwt.remainingSeconds(token)).isZero();
    }

    @Test
    @DisplayName("签名字节被改动后必须拒绝")
    void tamperedSignature() {
        JwtUtil jwt = util(1800);
        String token = jwt.issueAccess(1L, "admin", "ADMIN", null, null);

        // 改最后一个字符：Base64URL 的末位有填充位，改它不一定影响解码，
        // 所以改倒数第 3 个字符更可靠地破坏签名
        char[] chars = token.toCharArray();
        int idx = token.length() - 3;
        chars[idx] = chars[idx] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("换一把密钥签的令牌，用本服务密钥解析必须失败")
    void wrongKey() {
        JwtUtil issuer = new JwtUtil("another-secret-0123456789abcdefghijkl",
                Duration.ofMinutes(30), Duration.ofDays(7));
        JwtUtil verifier = util(1800);

        String token = issuer.issueAccess(1L, "admin", "ADMIN", null, null);
        assertThatThrownBy(() -> verifier.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("issuer 不匹配必须拒绝")
    void wrongIssuer() {
        JwtUtil jwt = util(1800);
        // 手工造一个 iss 不对的令牌
        String token = Jwts.builder()
                .issuer("someone-else")
                .subject("1")
                .id("jti-x")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("密钥短于 32 字节直接拒绝启动")
    void shortSecret() {
        assertThatThrownBy(() -> new JwtUtil("too-short",
                Duration.ofMinutes(30), Duration.ofDays(7)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("access 与 refresh 的 typ 可区分")
    void tokenType() {
        JwtUtil jwt = util(1800);
        assertThat(jwt.toPayload(jwt.parse(jwt.issueAccess(1L, "u", "TEACHER", 2L, null))).tokenType())
                .isEqualTo(JwtUtil.TYPE_ACCESS);
        assertThat(jwt.toPayload(jwt.parse(jwt.issueRefresh(1L, "u", "TEACHER", 2L, null))).tokenType())
                .isEqualTo(JwtUtil.TYPE_REFRESH);
    }
}
