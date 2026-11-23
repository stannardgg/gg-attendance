package com.fa.org.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa.infra.audit.AuditWriter;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.org.api.dto.ScopeDtos;
import com.fa.org.domain.FaceConsentRecord;
import com.fa.org.domain.FaceProfile;
import com.fa.org.domain.FaceVector;
import com.fa.org.domain.Student;
import com.fa.org.infra.FaceConsentRecordMapper;
import com.fa.org.infra.FaceDuplicateChecker;
import com.fa.org.infra.FaceProfileMapper;
import com.fa.org.infra.FaceVectorMapper;
import com.fa.org.infra.StudentMapper;
import com.fa.org.infra.VectorCrypto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人脸采集落库（CR-009）。
 *
 * <p>Java 没有模型，算不出 embedding，所以链路是：
 * 移动端逐张 POST {@code /face-api/enroll/sample} → face 服务质检并算出 512 维向量，
 * 以 {@code sampleKey} 为键把向量暂存进 Redis（TTL 15 分钟，DB 0）→
 * 移动端凑够 5~10 张后 POST {@code /api/v1/mobile/face/enroll} →
 * 本类按 key 取回向量，写 {@code face_vector} 并把 {@code face_profile} 置为 {@code PENDING_REVIEW}。
 *
 * <p>为什么让 face 服务把向量写 Redis 而不是让它回调 Java：
 * <ul>
 *   <li>契约 {@code FaceEnrollReq} 只有 {@code sampleKeys}，没有向量字段——向量不该穿过
 *       移动端浏览器（既不安全，也会因 base64 让 10 张样本的请求体膨胀到几 MB）；</li>
 *   <li>由 Java 单侧写库，保持"face_vector 只有一个写入口"（与 CR-002 的只读快照对称）；</li>
 *   <li>样本图本身由 face 服务校验，Java 只做归属与状态机，不重复实现质检。</li>
 * </ul>
 *
 * <p>状态机约束：{@code PENDING_REVIEW} / {@code ACTIVE} 期间不接受重新采集——
 * 前者会让审核人对着新旧混合的样本做判断，后者会把一个能签到的人瞬间打回待审核，
 * 正在上课的学生会直接签不上。要换照片必须先被驳回（REJECTED）。
 *
 * <p>一人一档（CR-014）：落库前先过 {@link FaceDuplicateChecker}，同一张脸已经在别人档案里
 * （{@code ACTIVE}/{@code PENDING_REVIEW}）就整单拒绝，<b>不写</b>同意记录、向量与档案状态。
 * 放在这里而不是审核环节：审核人看到的只有样本图，判断不了「这张脸是否已被另一个学号占用」，
 * 等审核时才发现，样本已经躺在库里了。
 */
@Service
public class FaceEnrollService {

    private static final Logger log = LoggerFactory.getLogger(FaceEnrollService.class);

    /** 与 face 服务 {@code ENROLL_KEY_PREFIX} 必须一致。 */
    static final String SAMPLE_KEY_PREFIX = "fa:enroll:sample:";

    /**
     * 当前生效的《人脸信息采集与使用告知》版本（全文见 docs/compliance/face-consent-notice-v2.md）。
     * 与移动端 EnrollView 的 {@code CONSENT_VERSION} 必须同步升级；升版时旧同意不自动顺延，
     * 学生须重新阅读并同意（CR-028）。
     */
    public static final String CONSENT_VERSION_CURRENT = "v2";

    private static final int MIN_SAMPLES = 5;
    private static final int MAX_SAMPLES = 10;
    private static final int EXPECTED_DIM = 512;

    private final FaceProfileMapper profileMapper;
    private final FaceVectorMapper vectorMapper;
    private final FaceConsentRecordMapper consentMapper;
    private final StudentMapper studentMapper;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final AuditWriter audit;
    private final ObjectMapper json;
    private final VectorCrypto vectorCrypto;
    private final FaceDuplicateChecker duplicateChecker;

    public FaceEnrollService(FaceProfileMapper profileMapper, FaceVectorMapper vectorMapper,
                             FaceConsentRecordMapper consentMapper, StudentMapper studentMapper,
                             StringRedisTemplate redis,
                             JdbcTemplate jdbc, AuditWriter audit, ObjectMapper json,
                             VectorCrypto vectorCrypto, FaceDuplicateChecker duplicateChecker) {
        this.profileMapper = profileMapper;
        this.vectorMapper = vectorMapper;
        this.consentMapper = consentMapper;
        this.studentMapper = studentMapper;
        this.redis = redis;
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
        this.vectorCrypto = vectorCrypto;
        this.duplicateChecker = duplicateChecker;
    }

    /** 本人档案状态 + 当前同意态（CR-028）；从未采集返回 NONE（不建行，读接口不该有写副作用）。 */
    public ScopeDtos.FaceStatusResp status(long studentId) {
        Student student = studentMapper.selectById(studentId);
        AccountConsent consent = loadConsent(student == null ? null : student.getAccountId());
        FaceProfile p = findProfile(studentId);
        if (p == null) {
            return new ScopeDtos.FaceStatusResp(studentId, "NONE", 0, null, null, null,
                    consent.version(), consent.at());
        }
        return toResp(p, consent);
    }

    /**
     * 记录「单独同意」（CR-028）。
     *
     * <p>PIPL §29：人脸属敏感个人信息，处理前须取得「单独同意」——必须是数据主体
     * 针对本事项的显式动作（阅读全文后点「同意」），勾选框、静默继续、与其他协议
     * 捆绑勾选都不构成。同意事件写 {@code face_consent_record}（只增不改），
     * {@code account.face_consent_*} 只保留当前态供展示。
     *
     * <p>幂等：同一账号 + 同一版本已有 ACCEPT 时不产生新事件、不刷新原同意时间——
     * 同意时间必须反映第一次同意，重复点击不能洗掉时间线。
     */
    @Transactional
    public ScopeDtos.FaceStatusResp consent(long studentId, String consentVersion) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        if (student.getAccountId() == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号状态异常，无法记录同意");
        }
        if (!CONSENT_VERSION_CURRENT.equals(consentVersion)) {
            throw new BizException(ErrorCode.FACE_CONSENT_REQUIRED, "该同意书版本不存在或已停用，请刷新页面后重新阅读");
        }
        Long existed = consentMapper.selectCount(Wrappers.<FaceConsentRecord>lambdaQuery()
                .eq(FaceConsentRecord::getAccountId, student.getAccountId())
                .eq(FaceConsentRecord::getAction, "ACCEPT")
                .eq(FaceConsentRecord::getConsentVersion, consentVersion));
        if (existed == null || existed == 0) {
            LocalDateTime now = LocalDateTime.now();
            FaceConsentRecord r = new FaceConsentRecord();
            r.setAccountId(student.getAccountId());
            r.setStudentId(studentId);
            r.setAction("ACCEPT");
            r.setConsentVersion(consentVersion);
            r.setCreatedAt(now);
            consentMapper.insert(r);
            jdbc.update("update account set face_consent_at = ?, face_consent_version = ? where id = ?",
                    now, consentVersion, student.getAccountId());
            audit.write("FACE_CONSENT", "STUDENT", studentId, "记录人脸采集单独同意（" + consentVersion + "）");
            log.info("face consent recorded studentId={} version={}", studentId, consentVersion);
        }
        return status(studentId);
    }

    /**
     * 数据主体行使删除权（CR-028）：本人删除人脸向量与档案，并撤回采集同意。
     *
     * <p>语义边界：删除对象是人脸相关数据（加密向量、档案状态、同意状态）；
     * 由既往签到产生的考勤记录属考勤业务数据，不随本次删除清除（告知书 v2 已写明）。
     * 删除权不因审核流程未走完而受限——PENDING_REVIEW / REJECTED / ACTIVE 任意状态
     * 都可以删，删后回到 NONE，可随时重新采集（重新采集须重新同意）。
     */
    @Transactional
    public ScopeDtos.FaceStatusResp deleteOwnData(long studentId) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        FaceProfile profile = findProfile(studentId);
        Long vectorCount = vectorMapper.selectCount(
                Wrappers.<FaceVector>lambdaQuery().eq(FaceVector::getStudentId, studentId));
        boolean hasData = (vectorCount != null && vectorCount > 0)
                || (profile != null && !"NONE".equals(profile.getStatus()));
        if (!hasData) {
            throw new BizException(ErrorCode.NOT_FOUND, "暂无人脸数据可删除");
        }
        AccountConsent current = loadConsent(student.getAccountId());
        // 敏感数据本体：整批物理删除（向量加密存储，删除后即刻不可恢复）
        vectorMapper.delete(Wrappers.<FaceVector>lambdaQuery().eq(FaceVector::getStudentId, studentId));
        if (profile != null) {
            profileMapper.deleteById(profile.getId());
        }
        if (student.getAccountId() != null) {
            FaceConsentRecord r = new FaceConsentRecord();
            r.setAccountId(student.getAccountId());
            r.setStudentId(studentId);
            r.setAction("WITHDRAW");
            r.setConsentVersion(current.version());
            r.setCreatedAt(LocalDateTime.now());
            consentMapper.insert(r);
            jdbc.update("update account set face_consent_at = NULL, face_consent_version = NULL where id = ?",
                    student.getAccountId());
        }
        audit.write("FACE_DATA_DELETE", "STUDENT", studentId,
                "学生本人删除人脸数据并撤回同意（删除向量 " + (vectorCount == null ? 0 : vectorCount) + " 条）");
        log.info("face data deleted by owner studentId={} vectors={}", studentId, vectorCount);
        return status(studentId);
    }

    @Transactional
    public ScopeDtos.FaceStatusResp enroll(long studentId, ScopeDtos.FaceEnrollReq req) {
        Student student = studentMapper.selectById(studentId);
        if (student == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学生不存在");
        }
        FaceProfile profile = findProfile(studentId);
        String current = profile == null ? "NONE" : profile.getStatus();
        if ("PENDING_REVIEW".equals(current)) {
            throw new BizException(ErrorCode.CODE_DUPLICATED, "已提交过采集，请等老师审核");
        }
        if ("ACTIVE".equals(current)) {
            // 已通过审核的人重新采集会让他在审核期间无法签到，不做"静默降级"
            throw new BizException(ErrorCode.CODE_DUPLICATED, "人脸已通过审核，无需重复采集");
        }

        // 单独同意（§13.4 / CR-028）：服务端强制——同意必须是本人对当前版本文本的
        // 显式动作且已入事件表，勾选框或前端开关不作为依据。FA-4010 从这里兜底；
        // 被拒请求不会留下向量、档案状态或同意事件。
        if (!CONSENT_VERSION_CURRENT.equals(req.consentVersion())) {
            throw new BizException(ErrorCode.FACE_CONSENT_REQUIRED, "同意书版本已更新，请刷新页面重新阅读并同意");
        }
        if (student.getAccountId() == null) {
            throw new BizException(ErrorCode.FACE_CONSENT_REQUIRED, "账号状态异常，无法确认采集同意");
        }
        Long accepted = consentMapper.selectCount(Wrappers.<FaceConsentRecord>lambdaQuery()
                .eq(FaceConsentRecord::getAccountId, student.getAccountId())
                .eq(FaceConsentRecord::getAction, "ACCEPT")
                .eq(FaceConsentRecord::getConsentVersion, CONSENT_VERSION_CURRENT));
        if (accepted == null || accepted == 0) {
            throw new BizException(ErrorCode.FACE_CONSENT_REQUIRED,
                    "未取得有效的人脸采集单独同意，请先在采集页阅读告知并点「同意」");
        }

        List<String> keys = normalizeKeys(req.sampleKeys());
        List<FaceVector> vectors = new ArrayList<>(keys.size());
        for (String key : keys) {
            vectors.add(readSample(studentId, key));
        }

        // 一人一档（CR-014）：命中他人档案就整单拒绝，此时还没写任何东西。
        // （同意记录由独立的 consent 端点在采集前落库，与本请求解耦，CR-028。）
        List<byte[]> probes = new ArrayList<>(vectors.size());
        for (FaceVector v : vectors) {
            probes.add(v.getEmbedding());
        }
        duplicateChecker.findConflict(studentId, probes).ifPresent(m -> {
            throw new BizException(ErrorCode.FACE_ALREADY_ENROLLED,
                    "这张人脸已用于其他学生档案，请联系老师核实");
        });

        // 单独同意已在 consent 端点落事件表并通过上方校验；这里不再重复写 account 字段（CR-028）

        // 驳回后重采：旧向量整批替换，避免与旧照片混用同一份比对集
        vectorMapper.delete(Wrappers.<FaceVector>lambdaQuery().eq(FaceVector::getStudentId, studentId));
        LocalDateTime now = LocalDateTime.now();
        for (FaceVector v : vectors) {
            v.setStudentId(studentId);
            v.setCreatedAt(now);
            // CR-012：落库前字段级加密（未配置密钥时为明文，VectorCrypto 启动时已 WARN）
            v.setEmbedding(vectorCrypto.encrypt(v.getEmbedding()));
            vectorMapper.insert(v);
        }

        FaceProfile target = profile == null ? new FaceProfile() : profile;
        target.setStudentId(studentId);
        target.setStatus("PENDING_REVIEW");
        target.setSampleCount(vectors.size());
        target.setSubmittedAt(now);
        target.setReviewedBy(null);
        target.setReviewedAt(null);
        target.setRejectReason(null);
        if (target.getId() == null) {
            profileMapper.insert(target);
        } else {
            profileMapper.updateById(target);
        }

        audit.write("FACE_ENROLL", "STUDENT", studentId, "提交人脸采集 " + vectors.size() + " 张");
        log.info("face enroll studentId={} samples={}", studentId, vectors.size());
        return toResp(target, loadConsent(student.getAccountId()));
    }

    // ---------------- 内部 ----------------

    private FaceProfile findProfile(long studentId) {
        return profileMapper.selectOne(
                Wrappers.<FaceProfile>lambdaQuery().eq(FaceProfile::getStudentId, studentId));
    }

    private static ScopeDtos.FaceStatusResp toResp(FaceProfile p, AccountConsent consent) {
        return new ScopeDtos.FaceStatusResp(p.getStudentId(), p.getStatus(), p.getSampleCount(),
                p.getSubmittedAt(), p.getReviewedAt(), p.getRejectReason(),
                consent.version(), consent.at());
    }

    /** account 表上的当前同意态（仅展示用；事实来源是 face_consent_record 事件流）。 */
    private record AccountConsent(String version, LocalDateTime at) {
        static AccountConsent empty() {
            return new AccountConsent(null, null);
        }
    }

    private AccountConsent loadConsent(Long accountId) {
        if (accountId == null) {
            return AccountConsent.empty();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select face_consent_version, face_consent_at from account where id = ?", accountId);
        if (rows.isEmpty()) {
            return AccountConsent.empty();
        }
        Map<String, Object> row = rows.get(0);
        Object at = row.get("face_consent_at");
        LocalDateTime atTime;
        if (at == null) {
            atTime = null;
        } else if (at instanceof LocalDateTime ldt) {
            atTime = ldt;
        } else if (at instanceof java.sql.Timestamp ts) {
            atTime = ts.toLocalDateTime();
        } else {
            atTime = null;
        }
        return new AccountConsent((String) row.get("face_consent_version"), atTime);
    }

    private static List<String> normalizeKeys(List<String> raw) {
        if (raw == null || raw.size() < MIN_SAMPLES || raw.size() > MAX_SAMPLES) {
            throw new BizException(ErrorCode.NO_FACE, "请提交 " + MIN_SAMPLES + "~" + MAX_SAMPLES + " 张合格样本");
        }
        Set<String> seen = new HashSet<>();
        List<String> keys = new ArrayList<>(raw.size());
        for (String k : raw) {
            if (k == null || k.isBlank()) {
                throw new BizException(ErrorCode.NO_FACE, "样本标识不能为空");
            }
            String trimmed = k.trim();
            if (!seen.add(trimmed)) {
                throw new BizException(ErrorCode.NO_FACE, "样本重复提交");
            }
            keys.add(trimmed);
        }
        return keys;
    }

    /**
     * 从 Redis 取回 face 服务算好的向量。
     *
     * <p>取不到只有两种可能：样本超过 TTL 被回收，或客户端伪造了 key。
     * 两种情况都只能要求重新采集——没有向量就无法比对，硬塞一条空向量进来比报错更糟。
     */
    private FaceVector readSample(long studentId, String sampleKey) {
        String raw = redis.opsForValue().get(SAMPLE_KEY_PREFIX + sampleKey);
        if (raw == null) {
            throw new BizException(ErrorCode.NO_FACE, "采集样本已过期，请重新采集");
        }
        try {
            JsonNode node = json.readTree(raw);
            byte[] embedding = Base64.getDecoder().decode(node.path("embedding").asText());
            int dim = node.path("dim").asInt(EXPECTED_DIM);
            if (dim != EXPECTED_DIM || embedding.length != EXPECTED_DIM * Float.BYTES) {
                throw new BizException(ErrorCode.NO_FACE, "样本特征维度异常，请重新采集");
            }
            FaceVector v = new FaceVector();
            v.setModelName(truncate(node.path("model").asText("buffalo_l")));
            v.setDim(dim);
            v.setEmbedding(embedding);
            v.setQualityScore(BigDecimal.valueOf(node.path("qualityScore").asDouble(0.9))
                    .setScale(3, RoundingMode.HALF_UP));
            if (node.hasNonNull("brightness")) {
                v.setBrightness(BigDecimal.valueOf(node.path("brightness").asDouble())
                        .setScale(2, RoundingMode.HALF_UP));
            }
            v.setSourceRef(sampleKey);
            return v;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("bad enroll sample payload studentId={}", studentId, e);
            throw new BizException(ErrorCode.NO_FACE, "样本数据损坏，请重新采集");
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 32 ? s : s.substring(0, 32);
    }
}
