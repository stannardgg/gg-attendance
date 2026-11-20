package com.fa.org.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VectorCrypto 的纯逻辑测试：不起 Spring 上下文、不连库。
 * 512 维 float32 = 2048 字节明文，与 FaceEnrollService.EXPECTED_DIM 对齐。
 */
class VectorCryptoTest {

    private static final int PLAIN_LEN = 512 * Float.BYTES;

    private static String newKeyB64() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static byte[] samplePlain() {
        byte[] plain = new byte[PLAIN_LEN];
        new SecureRandom().nextBytes(plain);
        return plain;
    }

    @Test
    @DisplayName("配了密钥：enabled=true，密文加解密往返一致")
    void roundTrip() {
        VectorCrypto crypto = new VectorCrypto(newKeyB64());
        assertThat(crypto.enabled()).isTrue();

        byte[] plain = samplePlain();
        byte[] stored = crypto.encrypt(plain);

        // 密文布局：版本 1B + IV 12B + 密文 + tag 16B = 明文 + 29B，且首字节是版本号
        assertThat(stored).hasSize(plain.length + VectorCrypto.OVERHEAD);
        assertThat(stored[0]).isEqualTo(VectorCrypto.VERSION);

        assertThat(crypto.decrypt(stored, plain.length)).isEqualTo(plain);
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（IV 随机）——GCM 下 IV 重用是灾难性错误")
    void uniqueIvPerCall() {
        VectorCrypto crypto = new VectorCrypto(newKeyB64());
        byte[] plain = samplePlain();
        assertThat(crypto.encrypt(plain)).isNotEqualTo(crypto.encrypt(plain));
    }

    @Test
    @DisplayName("密文被篡改一位：GCM 认证失败必须报错，不得静默交出坏数据")
    void tamperDetected() {
        VectorCrypto crypto = new VectorCrypto(newKeyB64());
        byte[] stored = crypto.encrypt(samplePlain());
        stored[stored.length - 1] ^= 0x01;
        assertThatThrownBy(() -> crypto.decrypt(stored, PLAIN_LEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tag");
    }

    @Test
    @DisplayName("换了密钥（密钥丢失/换库场景）：解密报错而不是返回垃圾")
    void wrongKeyFails() {
        byte[] stored = new VectorCrypto(newKeyB64()).encrypt(samplePlain());
        VectorCrypto other = new VectorCrypto(newKeyB64());
        assertThatThrownBy(() -> other.decrypt(stored, PLAIN_LEN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("存量明文行兼容：配了密钥也原样返回，存量数据无需停机迁移")
    void legacyPlaintextPassthrough() {
        VectorCrypto crypto = new VectorCrypto(newKeyB64());
        byte[] plain = samplePlain();
        assertThat(crypto.decrypt(plain, plain.length)).isSameAs(plain);
    }

    @Test
    @DisplayName("首字节碰巧等于版本号的明文：靠长度双硬条件区分，不会误判成密文")
    void plainWithVersionLikeFirstByte() {
        VectorCrypto crypto = new VectorCrypto(newKeyB64());
        byte[] plain = new byte[PLAIN_LEN];
        plain[0] = VectorCrypto.VERSION;
        assertThat(crypto.decrypt(plain, plain.length)).isSameAs(plain);
    }

    @Test
    @DisplayName("未配置密钥：enabled=false，encrypt/decrypt 原样透传（本地/CI 行为）")
    void noKeyPassthrough() {
        VectorCrypto crypto = new VectorCrypto("");
        assertThat(crypto.enabled()).isFalse();

        byte[] plain = samplePlain();
        assertThat(crypto.encrypt(plain)).isSameAs(plain);
        assertThat(crypto.decrypt(plain, plain.length)).isSameAs(plain);
    }

    @Test
    @DisplayName("未配置密钥但库里是密文：报错提示密钥丢失，绝不能把密文当明文下发")
    void noKeyButCipherPresent() {
        byte[] stored = new VectorCrypto(newKeyB64()).encrypt(samplePlain());
        VectorCrypto noKey = new VectorCrypto("  ");
        assertThatThrownBy(() -> noKey.decrypt(stored, PLAIN_LEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FA_VECTOR_AES_KEY");
    }

    @Test
    @DisplayName("密钥非法（不是 base64 / 长度不对）：启动即失败，不留到第一次加密才炸")
    void invalidKeyFailsFast() {
        assertThatThrownBy(() -> new VectorCrypto("not-base64!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> new VectorCrypto(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
