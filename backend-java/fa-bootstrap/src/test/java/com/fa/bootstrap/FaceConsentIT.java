package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fa.org.app.FaceEnrollService;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * CR-028：单独同意事件、数据主体删除权、归档清脸。
 *
 * <p>覆盖：未同意 enroll 被 FA-4010 拒（服务端强校验）、旧版本同意/提交被拒、
 * 同意事件落库与幂等（重复同意不刷新首次时间）、删除权级联（向量/档案/同意态）
 * 与二次删除 404、归档学生同步清脸。enroll 的成功链路需要真实 face 服务算向量，
 * 不在此覆盖（由 e2e 覆盖）。
 *
 * <p>向量与档案状态用 jdbc 直写模拟「已入库」，与 VectorSyncIT 同思路：
 * 这里被测的是 consent/deletion 的 Java 编排，不是向量本身。
 */
class FaceConsentIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";

    @Autowired
    private JdbcTemplate jdbc;

    /** 建班→建学生→学生激活（返回 STUDENT 令牌）。 */
    private record StudentCtx(long studentId, String studentNo, String name, String token) {
    }

    private StudentCtx createAndActivate(String s) {
        String admin = login(ADMIN, ADMIN_PWD);
        ResponseEntity<JsonNode> cr = post("/api/v1/admin/classes",
                Map.of("code", "C" + s, "name", "同意班 " + s), admin);
        assertThat(cr.getStatusCode().value()).isEqualTo(200);
        long classId = cr.getBody().path("data").path("id").asLong();
        String classCode = cr.getBody().path("data").path("code").asText();

        String studentNo = "FC" + s;
        String name = "同意生" + s;
        ResponseEntity<JsonNode> sr = post("/api/v1/admin/students",
                Map.of("studentNo", studentNo, "name", name, "classId", classId), admin);
        assertThat(sr.getStatusCode().value()).isEqualTo(200);
        long studentId = sr.getBody().path("data").path("id").asLong();

        // 学生自助激活（D5）：三要素 + 密码；激活即发 STUDENT 令牌
        ResponseEntity<JsonNode> ar = post("/api/v1/auth/student/activate",
                Map.of("studentNo", studentNo, "name", name, "classCode", classCode,
                        "password", "Consent@123"), null);
        assertThat(ar.getStatusCode().value())
                .as("学生激活应成功，实际 body=%s", ar.getBody()).isEqualTo(200);
        String token = ar.getBody().path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return new StudentCtx(studentId, studentNo, name, token);
    }

    private byte[] normalized512() {
        ByteBuffer buf = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 512; i++) {
            buf.putFloat(i == 0 ? 1f : 0f);
        }
        return buf.array();
    }

    /** 模拟「已通过审核且向量已入库」的学生。 */
    private void seedActiveProfile(long studentId) {
        jdbc.update("update face_profile set status = 'ACTIVE', sample_count = 5 where student_id = ?",
                studentId);
        jdbc.update("""
                INSERT INTO face_vector (student_id, model_name, dim, embedding, quality_score)
                VALUES (?, 'buffalo_l', 512, ?, 0.900)
                """, studentId, normalized512());
    }

    @Test
    @DisplayName("未同意 enroll 400 FA-4010；版本不符被拒；同意幂等；删除级联；二次删除 404")
    void consentAndDeletionLifecycle() {
        StudentCtx ctx = createAndActivate(suffix());
        String v = FaceEnrollService.CONSENT_VERSION_CURRENT;

        // 1) 初始状态：NONE、无同意态
        ResponseEntity<JsonNode> st = get("/api/v1/mobile/face/status", ctx.token());
        assertThat(code(st)).isEqualTo("FA-0000");
        assertThat(st.getBody().path("data").path("status").asText()).isEqualTo("NONE");
        assertThat(st.getBody().path("data").path("consentVersion").isNull()).isTrue();

        // 2) 未同意直接 enroll → 400 FA-4010（样本键是占位：同意校验在样本取回之前）
        ResponseEntity<JsonNode> noConsent = post("/api/v1/mobile/face/enroll",
                Map.of("sampleKeys", List.of("k1", "k2", "k3", "k4", "k5"),
                        "consentVersion", v), ctx.token());
        assertThat(noConsent.getStatusCode().value()).isEqualTo(400);
        assertThat(code(noConsent)).isEqualTo("FA-4010");

        // 3) 旧版本：同意被拒、提交也被拒（旧同意不自动顺延）
        ResponseEntity<JsonNode> staleConsent = post("/api/v1/mobile/face/consent",
                Map.of("consentVersion", "v0-stale"), ctx.token());
        assertThat(staleConsent.getStatusCode().value()).isEqualTo(400);
        assertThat(code(staleConsent)).isEqualTo("FA-4010");
        ResponseEntity<JsonNode> staleEnroll = post("/api/v1/mobile/face/enroll",
                Map.of("sampleKeys", List.of("k1", "k2", "k3", "k4", "k5"),
                        "consentVersion", "v0-stale"), ctx.token());
        assertThat(staleEnroll.getStatusCode().value()).isEqualTo(400);
        assertThat(code(staleEnroll)).isEqualTo("FA-4010");

        // 4) 正常同意 → 事件表 1 行 ACCEPT + account 当前态
        ResponseEntity<JsonNode> ok = post("/api/v1/mobile/face/consent",
                Map.of("consentVersion", v), ctx.token());
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().path("data").path("consentVersion").asText()).isEqualTo(v);
        assertThat(ok.getBody().path("data").path("consentAt").isNull()).isFalse();
        String consentAtFirst = ok.getBody().path("data").path("consentAt").asText();
        Long accepts = jdbc.queryForObject(
                "select count(*) from face_consent_record where account_id = (select account_id from student where id = ?) and action = 'ACCEPT'",
                Long.class, ctx.studentId());
        assertThat(accepts).isEqualTo(1);

        // 5) 幂等：重复同意不产生新事件、不刷新首次同意时间
        ResponseEntity<JsonNode> again = post("/api/v1/mobile/face/consent",
                Map.of("consentVersion", v), ctx.token());
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().path("data").path("consentAt").asText()).isEqualTo(consentAtFirst);
        Long acceptsAfterRepeat = jdbc.queryForObject(
                "select count(*) from face_consent_record where account_id = (select account_id from student where id = ?) and action = 'ACCEPT'",
                Long.class, ctx.studentId());
        assertThat(acceptsAfterRepeat).isEqualTo(1);

        // 6) 模拟已入库：ACTIVE 档案 + 一条向量，此时可删除的数据存在
        seedActiveProfile(ctx.studentId());

        // 7) 删除权：一键物理删除向量与档案并撤回同意
        ResponseEntity<JsonNode> del = post("/api/v1/mobile/face/data-deletion", Map.of(), ctx.token());
        assertThat(del.getStatusCode().value()).isEqualTo(200);
        assertThat(del.getBody().path("data").path("status").asText()).isEqualTo("NONE");
        assertThat(del.getBody().path("data").path("consentVersion").isNull()).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from face_vector where student_id = ?", Long.class, ctx.studentId())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from face_profile where student_id = ?", Long.class, ctx.studentId())).isZero();
        Long withdraws = jdbc.queryForObject(
                "select count(*) from face_consent_record where student_id = ? and action = 'WITHDRAW'",
                Long.class, ctx.studentId());
        assertThat(withdraws).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from account a join student s on s.account_id = a.id "
                        + "where s.id = ? and a.face_consent_at is null and a.face_consent_version is null",
                Long.class, ctx.studentId())).isEqualTo(1);

        // 8) 二次删除 → 404 FA-2001（无数据可删）
        ResponseEntity<JsonNode> delAgain = post("/api/v1/mobile/face/data-deletion", Map.of(), ctx.token());
        assertThat(delAgain.getStatusCode().value()).isEqualTo(404);
        assertThat(code(delAgain)).isEqualTo("FA-2001");
    }

    @Test
    @DisplayName("归档学生（有考勤记录）同步物理清除人脸数据（CR-028）")
    void archiveCascadesFaceData() {
        StudentCtx ctx = createAndActivate(suffix());
        seedActiveProfile(ctx.studentId());
        // 造一条考勤记录 → 硬删除走归档分支（有考勤记录不能物理删学生）
        jdbc.update("INSERT INTO attendance (session_id, student_id, status) VALUES (0, ?, 'PRESENT')",
                ctx.studentId());

        ResponseEntity<JsonNode> del = delete("/api/v1/admin/students/" + ctx.studentId(),
                login(ADMIN, ADMIN_PWD));
        assertThat(del.getStatusCode().value()).isEqualTo(200);
        assertThat(del.getBody().path("data").path("archived").asBoolean()).isTrue();

        // 学生行保留（归档），人脸数据物理清除
        assertThat(jdbc.queryForObject(
                "select count(*) from student where id = ?", Long.class, ctx.studentId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from face_vector where student_id = ?", Long.class, ctx.studentId())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from face_profile where student_id = ?", Long.class, ctx.studentId())).isZero();
    }
}
