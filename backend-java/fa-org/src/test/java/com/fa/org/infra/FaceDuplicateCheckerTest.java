package com.fa.org.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同脸查重的纯逻辑测试：不起 Spring、不连库。
 *
 * <p>覆盖三件事：小端 float32 的编解码、余弦的归一化、以及「跨候选 × 跨探针取最大」的聚合。
 * SQL 侧的过滤（排除本人 / REJECTED / 非 ACTIVE 学生）不在单测范围，由 CR-014 的实现说明约束。
 */
class FaceDuplicateCheckerTest {

    /** 把 float[] 编成库里那样的小端 float32。 */
    private static byte[] bytes(float... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    private static FaceDuplicateChecker.Candidate candidate(long studentId, float... vector) {
        return new FaceDuplicateChecker.Candidate(studentId, vector.length, bytes(vector));
    }

    @Test
    @DisplayName("同一张脸：余弦 1.0，高于阈值即命中")
    void sameFaceIsBlocked() {
        Optional<FaceDuplicateChecker.Match> hit = FaceDuplicateChecker.best(
                List.of(candidate(57, 1f, 0f, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f)),
                0.55);

        assertThat(hit).isPresent();
        assertThat(hit.get().studentId()).isEqualTo(57);
        assertThat(hit.get().similarity()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("不同的人：余弦 0.4 低于阈值，不拦")
    void differentFacePasses() {
        // 余弦 = 0.4 的两条单位向量
        double s = Math.sqrt(1 - 0.4 * 0.4);
        Optional<FaceDuplicateChecker.Match> hit = FaceDuplicateChecker.best(
                List.of(candidate(60, (float) 0.4, (float) s, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f)),
                0.55);

        assertThat(hit).isEmpty();
    }

    @Test
    @DisplayName("阈值取 >=：0.56 拦、0.54 不拦")
    void thresholdIsInclusive() {
        double above = Math.sqrt(1 - 0.56 * 0.56);
        double below = Math.sqrt(1 - 0.54 * 0.54);

        assertThat(FaceDuplicateChecker.best(
                List.of(candidate(61, (float) 0.56, (float) above, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f)), 0.55)).isPresent();

        assertThat(FaceDuplicateChecker.best(
                List.of(candidate(62, (float) 0.54, (float) below, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f)), 0.55)).isEmpty();
    }

    @Test
    @DisplayName("多张样本取最大：一张撞上就算同脸，不被其余相似度低的样本稀释")
    void maxAcrossProbes() {
        // 探针 1 与档案无关（0），探针 2 与档案同脸（1）——真实场景是同一人的不同角度
        Optional<FaceDuplicateChecker.Match> hit = FaceDuplicateChecker.best(
                List.of(candidate(70, 0f, 1f, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f), bytes(0f, 1f, 0f, 0f)),
                0.55);

        assertThat(hit).isPresent();
        assertThat(hit.get().similarity()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    @DisplayName("多个候选取最高，并带出冲突档案的 studentId")
    void picksHighestCandidate() {
        double mid = Math.sqrt(1 - 0.6 * 0.6);
        Optional<FaceDuplicateChecker.Match> hit = FaceDuplicateChecker.best(
                List.of(
                        candidate(71, (float) 0.6, (float) mid, 0f, 0f),
                        candidate(72, 1f, 0f, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f)),
                0.55);

        assertThat(hit).isPresent();
        assertThat(hit.get().studentId()).isEqualTo(72);
    }

    @Test
    @DisplayName("维度不符的向量跳过，不参与比对也不报错")
    void skipsDimensionMismatch() {
        // 声明 512 维、实际只给了 2 个 float —— decode 出来长度 2 != dim，必须跳过
        FaceDuplicateChecker.Candidate broken =
                new FaceDuplicateChecker.Candidate(80, 512, bytes(1f, 0f));

        Optional<FaceDuplicateChecker.Match> hit = FaceDuplicateChecker.best(
                List.of(broken, candidate(81, 1f, 0f, 0f, 0f)),
                List.of(bytes(1f, 0f, 0f, 0f)),
                0.55);

        assertThat(hit).isPresent();
        assertThat(hit.get().studentId()).isEqualTo(81);
    }

    @Test
    @DisplayName("坏样本：字节数不是 4 的倍数时解码为 null，探针全坏则直接放行")
    void badProbeBytes() {
        assertThat(FaceDuplicateChecker.decode(new byte[] {1, 2, 3})).isNull();

        Optional<FaceDuplicateChecker.Match> hit = FaceDuplicateChecker.best(
                List.of(candidate(90, 1f, 0f)),
                List.of(new byte[] {1, 2, 3}),
                0.55);

        assertThat(hit).isEmpty();
    }

    @Test
    @DisplayName("余弦对未归一化的向量同样成立")
    void cosineNormalizes() {
        assertThat(FaceDuplicateChecker.cosine(
                new float[] {2f, 0f, 0f, 0f},
                new float[] {7f, 0f, 0f, 0f})).isCloseTo(1.0, within(1e-9));

        assertThat(FaceDuplicateChecker.cosine(
                new float[] {1f, 0f}, new float[] {0f, 0f})).isZero();
    }
}
