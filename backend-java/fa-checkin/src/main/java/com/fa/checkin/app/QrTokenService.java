package com.fa.checkin.app;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 二维码 token（契约 §/admin/sessions/{id}/qrcode）：
 *
 * <pre>
 *   slice = floor(epoch / 15)
 *   token = Base32(HMAC-SHA256(SESSION_QR_SECRET, "{sessionId}|{qrEpoch}|{slice}"))[:16]
 * </pre>
 *
 * <p>为什么用「时间片」而不是每次请求随机：大屏每 15 秒拉一次，同一窗口内所有客户端
 * 必须拿到<b>同一个</b> token，否则大屏和学生看到的码对不上。
 *
 * <p>为什么 token 里带 qrEpoch：教师点「换码」时 epoch+1，旧码立刻失效，
 * 不需要维护一张已作废 token 的黑名单。
 */
@Service
public class QrTokenService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int WINDOW_SEC = 15;

    private final byte[] secret;

    public QrTokenService(@Value("${app.qr.secret:dev-qr-secret-change-me}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public static long slice(long epochSec) {
        return epochSec / WINDOW_SEC;
    }

    public String token(long sessionId, int qrEpoch, long slice) {
        String msg = sessionId + "|" + qrEpoch + "|" + slice;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return base32(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (Exception e) {
            // 算法固定存在，失败只可能是 JCE 环境被裁剪——直接抛，不要静默降级成可预测的码
            throw new IllegalStateException("二维码签名计算失败", e);
        }
    }

    public String currentToken(long sessionId, int qrEpoch) {
        return token(sessionId, qrEpoch, slice(Instant.now().getEpochSecond()));
    }

    /** 本窗口结束时刻（前端倒计时用），对齐到 15 秒边界。 */
    public Instant currentExpiresAt() {
        long now = Instant.now().getEpochSecond();
        return Instant.ofEpochSecond((slice(now) + 1) * WINDOW_SEC);
    }

    /**
     * 校验扫码 token。允许当前与上一个时间片：
     * 学生正好在窗口切换的瞬间扫码时会拿到上一片的码，只认当前片会误拒。
     */
    public boolean verify(long sessionId, int qrEpoch, String given) {
        if (given == null || given.length() != 16) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        String cur = token(sessionId, qrEpoch, slice(now));
        String prev = token(sessionId, qrEpoch, slice(now) - 1);
        return constantTimeEquals(cur, given) || constantTimeEquals(prev, given);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    /** RFC 4648 Base32，不填充。自己实现是为了不给一个 16 字符的 token 引入依赖。 */
    private static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }
}
