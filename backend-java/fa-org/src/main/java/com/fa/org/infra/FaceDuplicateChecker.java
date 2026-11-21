package com.fa.org.infra;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 建档同脸查重（CR-014）。
 *
 * <p>为什么需要：一人一档是 1:N 签到的隐含前提，但 B6 前建档只校验「本人是否已提交过」，
 * 没有任何跨学生约束。同一张脸被录进两个学号后，两个档案在比对时比分咬在一起，
 * {@code top1 - top2 >= 0.06} 的余量规则会把<b>本人</b>判成「像两个人」而拒签
 * （2026-11-21 现场：学号 57 与 158 同脸，相似度 0.692，两次签到均 FA-4005）。
 *
 * <p>为什么在 Java 侧算而不是让 face 服务算：
 * <ul>
 *   <li>face 的内存索引只有 {@code ACTIVE} 学生（CR-002 快照口径），而查重必须覆盖
 *       {@code PENDING_REVIEW}——先提交的两份档案都还没审核，索引里一条都没有；</li>
 *   <li>索引是 10~60 秒轮询的快照，刚落库的档案在窗口内查不到，靠它查重会漏；</li>
 *   <li>向量本就由 Java 解密（{@link VectorCrypto} 是解密边界），点积是纯算术，
 *       不引入第二个服务调用与新的跨服务契约。</li>
 * </ul>
 *
 * <p><b>不参与比对的行</b>：本人（重采时旧向量还没删）、非 ACTIVE 学生、档案状态
 * 不是 {@code ACTIVE}/{@code PENDING_REVIEW} 的（{@code REJECTED} 的向量是废样，
 * 拿它拦人会造成"拒绝过的人永远无法再建档"）。这些条件写在 SQL 里，见 {@link #findConflict}。
 *
 * <p>阈值 {@code FA_DUP_FACE_SIM} 默认 0.55。依据（2026-11-21 实测，107 名学生 /
 * 536 条向量 / 5565 个跨身份配对）：不同人最高 0.431，p99.9 = 0.389；同脸异档实测 0.692。
 * 取 0.55 留 0.12 的误伤余量，同时能抓住 0.6 以上的同脸——查重是兜底而不是门禁，
 * 宁可漏掉「同人换光后掉到 0.5~0.55」的边缘情况，也不要拦错人。
 */
@Component
public class FaceDuplicateChecker {

    private static final Logger log = LoggerFactory.getLogger(FaceDuplicateChecker.class);

    /** 与 sys_config / CR-014 对齐的默认阈值。 */
    static final double DEFAULT_THRESHOLD = 0.55;

    private final JdbcTemplate jdbc;
    private final VectorCrypto vectorCrypto;
    private final double threshold;

    public FaceDuplicateChecker(JdbcTemplate jdbc, VectorCrypto vectorCrypto,
                                @Value("${FA_DUP_FACE_SIM:0.55}") double threshold) {
        this.jdbc = jdbc;
        this.vectorCrypto = vectorCrypto;
        this.threshold = threshold;
    }

    /** 命中的冲突档案。相似度只在服务端日志里出现，不进响应、不进审计（§13.4）。 */
    public record Match(long studentId, double similarity) {}

    /**
     * 在他人档案里找与 {@code probes} 同脸的一条。
     *
     * @param studentId 本次建档的学生，比对时排除他自己的旧向量
     * @param probes    本次采集算出的明文向量（小端 float32），来自 Redis 暂存
     * @return 相似度最高且达到阈值的冲突档案；没有则 empty
     */
    public Optional<Match> findConflict(long studentId, List<byte[]> probes) {
        if (probes == null || probes.isEmpty()) {
            return Optional.empty();
        }
        // JOIN student / face_profile 的三条过滤缺一不可：
        //   1. 本人旧向量——驳回重采时 face_vector 还没整批替换，不排除会自己撞自己；
        //   2. 非 ACTIVE 学生——归档/删除的人不该继续占着这张脸；
        //   3. REJECTED 档案——废样不拦人，否则一次误拒就永久挡住重采。
        List<Candidate> candidates = jdbc.query("""
                SELECT fv.student_id, fv.dim, fv.embedding
                FROM face_vector fv
                JOIN student s ON s.id = fv.student_id AND s.status = 'ACTIVE'
                JOIN face_profile fp ON fp.student_id = fv.student_id
                WHERE fv.student_id <> ?
                  AND fp.status IN ('ACTIVE', 'PENDING_REVIEW')
                """,
                (rs, rowNum) -> {
                    int dim = rs.getInt("dim");
                    byte[] stored = rs.getBytes("embedding");
                    // CR-012 解密边界：与 InternalVectorController 共用 VectorCrypto
                    return new Candidate(rs.getLong("student_id"), dim,
                            vectorCrypto.decrypt(stored, dim * Float.BYTES));
                },
                studentId);

        Optional<Match> hit = best(candidates, probes, threshold);
        hit.ifPresent(m -> log.warn("face enroll blocked by duplicate face: studentId={} "
                        + "conflictWith={} similarity={} threshold={}",
                studentId, m.studentId(), String.format("%.3f", m.similarity()), threshold));
        return hit;
    }

    /** 一条已入库向量（已解密）。维度单独存，用于和探针长度对账。 */
    record Candidate(long studentId, int dim, byte[] embedding) {}

    /**
     * 跨候选、跨探针取最大余弦；只有达到阈值才算命中。
     *
     * <p>取 max 而不是均值：5~10 张样本里只要有一张与对方撞上，就说明是同一张脸——
     * 平均会被「同一个人不同角度」的其余样本稀释掉。
     */
    static Optional<Match> best(List<Candidate> candidates, List<byte[]> probes, double threshold) {
        List<float[]> decodedProbes = new ArrayList<>(probes.size());
        for (byte[] raw : probes) {
            float[] v = decode(raw);
            if (v != null) {
                decodedProbes.add(v);
            }
        }
        if (decodedProbes.isEmpty()) {
            return Optional.empty();
        }
        Match top = null;
        for (Candidate c : candidates) {
            float[] enrolled = decode(c.embedding());
            // 维度不符说明模型换代过，跨空间的余弦没有意义，跳过而不是硬算
            if (enrolled == null || enrolled.length != c.dim()) {
                continue;
            }
            for (float[] probe : decodedProbes) {
                if (probe.length != enrolled.length) {
                    continue;
                }
                double sim = cosine(probe, enrolled);
                if (top == null || sim > top.similarity()) {
                    top = new Match(c.studentId(), sim);
                }
            }
        }
        return top != null && top.similarity() >= threshold ? Optional.of(top) : Optional.empty();
    }

    /** 小端 float32 → float[]；长度不是 4 的倍数视为坏数据，返回 null。 */
    static float[] decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length % Float.BYTES != 0) {
            return null;
        }
        float[] out = new float[raw.length / Float.BYTES];
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    /**
     * 余弦相似度。两侧都做归一化——库里存的是归一化向量，但探针来自 Redis 暂存，
     * 不能假设它一定被归一化过（少一次归一化，点积就不再是余弦）。
     */
    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
