package com.fa.org.infra;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 人脸向量字段级加密（CR-012，AES-256-GCM）。
 *
 * <p>背景：face_vector 只落 512 维特征向量（原图在 face 服务内存中处理完即丢弃，
 * 不落盘），但向量在 PIPL 口径下仍属敏感个人信息，且存在模型反演风险，
 * 落库必须加密。选应用层字段加密而不是 MySQL TDE：社区版没有 TDE；
 * 选显式组件而不是 MyBatis TypeHandler：embedding 有两条访问路径——
 * MyBatis-Plus 写入（FaceEnrollService）和 JdbcTemplate 读取（InternalVectorController），
 * TypeHandler 只能覆盖前者，两条路径共用本类才不会漏。
 *
 * <p>密文布局：{@code 版本(1B) || IV(12B) || GCM 密文(含 16B 认证 tag)}。
 * IV 每次加密随机生成（GCM 下 IV 重用是灾难性错误，见 NIST SP 800-38D）。
 *
 * <p>密钥来源：环境变量 {@code FA_VECTOR_AES_KEY}（32 字节 key 的 base64，
 * 生成方式 {@code openssl rand -base64 32}），经 compose 注入 java 容器。
 * <b>未配置时保持明文落库并 WARN</b>——本地开发与 CI 没有密钥也能跑全链路，
 * 生产部署检查清单里必须确认该项已配置（§12.3）。
 *
 * <p>存量数据兼容：CR-012 之前的行是明文，解密时按「首字节 + 总长」双硬条件
 * 判定是否为密文（见 {@link #decrypt}），明文行原样返回，无需停机迁移；
 * 存量向量会在该学生下次整批重采时被加密替换（CR-009 的驳回重采逻辑）。
 */
@Component
public class VectorCrypto {

    private static final Logger log = LoggerFactory.getLogger(VectorCrypto.class);

    /** 密文版本字节；换密钥做轮换时可引入 0x02 表示新代际，解密按版本分发。 */
    static final byte VERSION = 0x01;

    /** GCM 推荐 12 字节 IV；tag 128 bit 是上限强度。 */
    static final int IV_LEN = 12;
    static final int TAG_BITS = 128;
    /** 密文头总长：版本 1B + IV 12B + tag 16B。 */
    static final int OVERHEAD = 1 + IV_LEN + 16;

    private static final int KEY_BYTES = 32;

    private final SecretKey key;

    public VectorCrypto(@Value("${FA_VECTOR_AES_KEY:}") String keyBase64) {
        if (keyBase64 == null || keyBase64.isBlank()) {
            this.key = null;
            log.warn("FA_VECTOR_AES_KEY 未配置：face_vector.embedding 将【明文】落库，"
                    + "生产部署必须配置（deploy/.env.example、CR-012）");
        } else {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(keyBase64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "FA_VECTOR_AES_KEY 不是合法 base64（生成方式：openssl rand -base64 32）", e);
            }
            if (raw.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "FA_VECTOR_AES_KEY 必须解码为 " + KEY_BYTES + " 字节，实际 " + raw.length);
            }
            this.key = new SecretKeySpec(raw, "AES");
            log.info("人脸向量字段级加密已启用：AES-256-GCM（密文 = 版本 1B + IV 12B + 密文 + tag 16B）");
        }
    }

    /** 仅供测试与启动自检断言用。 */
    boolean enabled() {
        return key != null;
    }

    /**
     * 落库前加密。未配置密钥时原样返回（启动时已 WARN，属可审计的降级而非静默）。
     */
    public byte[] encrypt(byte[] plain) {
        if (key == null || plain == null || plain.length == 0) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            // 注意：JCE 的 doFinal 输出已含 16B tag，容量按 版本+IV+ct 算，
            // 不能再加 OVERHEAD——否则尾部多 16B 零填充，decrypt 的长度判定直接失效
            return ByteBuffer.allocate(1 + IV_LEN + ct.length)
                    .put(VERSION).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("人脸向量加密失败", e);
        }
    }

    /**
     * 出库后解密（下发 Python 快照前还原明文，密文不出 MySQL 边界）。
     *
     * <p>是否为密文由两条<b>缺一不可</b>的硬条件判定：
     * 首字节 == {@link #VERSION} 且 总长 == 明文长 + {@link #OVERHEAD}。
     * 明文 float32 的首字节也可能碰巧等于 VERSION，但长度不可能同时巧合——
     * 单靠首字节判定会对存量明文产生约 1/256 的误判，把好人当密文去解会直接报错。
     *
     * <p>失败语义：认证 tag 不匹配（密钥不对 / 数据被篡改）必须抛异常，
     * 绝不把坏数据静默交给比对端——比错人比报错严重。
     */
    public byte[] decrypt(byte[] stored, int expectedPlainLen) {
        if (stored == null || stored.length == 0) {
            return stored;
        }
        boolean looksEncrypted = stored[0] == VERSION
                && stored.length == OVERHEAD + expectedPlainLen;
        if (!looksEncrypted) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException(
                    "检测到已加密的人脸向量，但 FA_VECTOR_AES_KEY 未配置——"
                    + "密钥丢失或换库，该批向量无法解密（CR-012）");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, IV_LEN));
            return cipher.doFinal(stored, 1 + IV_LEN, stored.length - 1 - IV_LEN);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("人脸向量解密失败：认证 tag 不匹配（密钥错误或数据被篡改）", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("人脸向量解密失败", e);
        }
    }
}
