package com.fa.checkin.app;

import com.fa.common.security.SecurityUtils;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * checkinTicket 签发。格式必须与 Python 侧 {@code app/common/tickets.py} 完全一致：
 *
 * <pre>
 *   base64url(json{"sid","studentId","jti","exp"}) + "." + hex(HMAC-SHA256(secret, payload))
 * </pre>
 *
 * <p>两侧默认密钥同为 {@code dev-internal-token}（环境变量 INTERNAL_TOKEN）。
 * 不一致的典型症状是 /face-api/challenge/start 一律 401——排查时先比对环境变量。
 *
 * <p>TTL 60 秒：票据只是「允许这个人对这次会话发起一次挑战」的短期凭证，
 * 长了等于给重放留窗口，短了学生走不到摄像头前。
 */
@Service
public class CheckinTicketService {

    private static final int TTL_SEC = 60;

    private final byte[] secret;

    public CheckinTicketService(@Value("${app.internal.token:dev-internal-token}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(long sessionId, long studentId) {
        String json = "{\"sid\":" + sessionId + ",\"studentId\":" + studentId
                + ",\"jti\":\"" + UUID.randomUUID().toString().replace("-", "") + "\""
                + ",\"exp\":" + (Instant.now().getEpochSecond() + TTL_SEC) + "}";
        String body = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    public int ttl() {
        return TTL_SEC;
    }

    /** 调试用：返回票据载荷，便于排查「票据有效但被拒」时到底是谁的问题。 */
    public Map<String, Object> inspect() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", "java");
        m.put("currentTeacherId", SecurityUtils.currentOrNull() == null
                ? null : SecurityUtils.currentOrNull().teacherId());
        m.put("ttlSec", TTL_SEC);
        return m;
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] out = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("票据签名失败", e);
        }
    }
}
