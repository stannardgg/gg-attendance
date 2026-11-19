package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * B4 签到主链路（把本地 21 项冒烟转成 CI 用例）。
 *
 * <p>只保留**回归价值高**的判据：快照应到、二维码轮换、异班拒绝、
 * commit 幂等、相似度下限、结束后不可 join。UI 细节不在这里测。
 */
class CheckinFlowIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";
    private static final String INTERNAL = "dev-internal-token";

    private ResponseEntity<JsonNode> internalPost(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    @Test
    @DisplayName("建会话→join→commit→幂等→相似度下限→异班→结束，全程 HTTP")
    void fullFlow() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();

        long cid = post("/api/v1/admin/classes",
                Map.of("code", "FC" + s, "name", "流程班" + s), admin)
                .getBody().path("data").path("id").asLong();
        long kid = post("/api/v1/admin/courses",
                Map.of("code", "FK" + s.toUpperCase(), "name", "流程课"), admin)
                .getBody().path("data").path("id").asLong();
        long tid = post("/api/v1/admin/teachers",
                Map.of("teacherNo", "FT" + s, "name", "流程教师", "password", "Teacher12345"), admin)
                .getBody().path("data").path("id").asLong();
        for (String[] pair : new String[][]{{"CLASS", String.valueOf(cid)}, {"COURSE", String.valueOf(kid)}}) {
            post("/api/v1/admin/teacher-scopes",
                    Map.of("teacherId", tid, "scopeType", pair[0], "scopeId", Long.parseLong(pair[1])), admin);
        }

        // 应到 2 人（本班），另建 1 人异班
        long s1 = post("/api/v1/admin/students",
                Map.of("studentNo", "FS" + s + "1", "name", "学生1", "classId", cid), admin)
                .getBody().path("data").path("id").asLong();
        long s2 = post("/api/v1/admin/students",
                Map.of("studentNo", "FS" + s + "2", "name", "学生2", "classId", cid), admin)
                .getBody().path("data").path("id").asLong();
        long otherCid = post("/api/v1/admin/classes",
                Map.of("code", "FO" + s, "name", "异班" + s), admin)
                .getBody().path("data").path("id").asLong();
        long outsider = post("/api/v1/admin/students",
                Map.of("studentNo", "FS" + s + "9", "name", "异班生", "classId", otherCid), admin)
                .getBody().path("data").path("id").asLong();

        String teacher = login("FT" + s, "Teacher12345");

        // 管理员不能发起
        ResponseEntity<JsonNode> denied = post("/api/v1/admin/sessions",
                Map.of("classId", cid, "courseId", kid, "title", "x", "durationMinutes", 30), admin);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("FA-1003");

        ResponseEntity<JsonNode> created = post("/api/v1/admin/sessions",
                Map.of("classId", cid, "courseId", kid, "title", "流程签到", "durationMinutes", 30), teacher);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        JsonNode sess = created.getBody().path("data");
        long sid = sess.path("id").asLong();
        assertThat(sess.path("expectedCount").asInt()).as("应到名单快照").isEqualTo(2);
        assertThat(sess.path("className").asText()).isNotBlank();
        assertThat(sess.path("courseName").asText()).isNotBlank();

        // 二维码
        JsonNode qr = get("/api/v1/admin/sessions/" + sid + "/qrcode", teacher).getBody().path("data");
        assertThat(qr.path("token").asText()).hasSize(16);
        int epoch = qr.path("qrEpoch").asInt();
        // 换码后旧 token 立刻失效（qrEpoch 进了签名），必须重新取一次
        JsonNode rotated = post("/api/v1/admin/sessions/" + sid + "/rotate-qr", null, teacher)
                .getBody().path("data");
        assertThat(rotated.path("qrEpoch").asInt()).isEqualTo(epoch + 1);
        String freshQr = get("/api/v1/admin/sessions/" + sid + "/qrcode", teacher)
                .getBody().path("data").path("token").asText();

        // 学生 join
        post("/api/v1/auth/student/activate", Map.of("studentNo", "FS" + s + "1", "name", "学生1",
                "classCode", "FC" + s, "password", "Student12345"), null);
        String stu = login("FS" + s + "1", "Student12345");
        // 旧码必须被拒（16.3 场景 3 的接口级版本）
        ResponseEntity<JsonNode> staleQr = post("/api/v1/mobile/sessions/" + sid + "/join",
                Map.of("qrToken", qr.path("token").asText()), stu);
        assertThat(staleQr.getStatusCode().value()).isEqualTo(400);
        assertThat(code(staleQr)).isEqualTo("FA-3002");

        ResponseEntity<JsonNode> join = post("/api/v1/mobile/sessions/" + sid + "/join",
                Map.of("qrToken", freshQr), stu);
        assertThat(join.getStatusCode().value()).isEqualTo(200);
        assertThat(join.getBody().path("data").path("checkinTicket").asText()).isNotBlank();

        // commit
        Map<String, Object> body = Map.of(
                "sessionId", sid, "studentId", s1, "similarity", 0.9123,
                "livenessMedian", 0.99, "identitySim", 0.97,
                "actionCount", 1, "actionSeq", "MOUTH_ONCE");
        ResponseEntity<JsonNode> commit = internalPost("/internal/checkin/commit", body);
        assertThat(commit.getStatusCode().value()).isEqualTo(200);
        assertThat(commit.getBody().path("data").path("status").asText()).isIn("PRESENT", "LATE");

        // 幂等
        ResponseEntity<JsonNode> dup = internalPost("/internal/checkin/commit", body);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("FA-3003");

        // 相似度下限（决策 A）
        ResponseEntity<JsonNode> low = internalPost("/internal/checkin/commit",
                Map.of("sessionId", sid, "studentId", s2, "similarity", 0.41, "actionCount", 1));
        assertThat(low.getStatusCode().value()).isEqualTo(400);
        assertThat(code(low)).isEqualTo("FA-4005");

        // 异班
        ResponseEntity<JsonNode> outsiderResp = internalPost("/internal/checkin/commit",
                Map.of("sessionId", sid, "studentId", outsider, "similarity", 0.9, "actionCount", 1));
        assertThat(outsiderResp.getStatusCode().value()).isEqualTo(403);
        assertThat(code(outsiderResp)).isEqualTo("FA-3004");

        // 结束 + 幂等 + 计数
        assertThat(post("/api/v1/admin/sessions/" + sid + "/close", null, teacher)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(post("/api/v1/admin/sessions/" + sid + "/close", null, teacher)
                .getStatusCode().value()).isEqualTo(200);
        JsonNode closed = get("/api/v1/admin/sessions/" + sid, teacher).getBody().path("data");
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("presentCount").asInt() + closed.path("lateCount").asInt()).isEqualTo(1);
        assertThat(closed.path("absentCount").asInt()).as("未签到的应到学生补 ABSENT").isEqualTo(1);

        // 结束后不能再 join
        ResponseEntity<JsonNode> afterClose = post("/api/v1/mobile/sessions/" + sid + "/join",
                Map.of("qrToken", freshQr), stu);
        assertThat(afterClose.getStatusCode().value()).isEqualTo(409);
        assertThat(code(afterClose)).isEqualTo("FA-3001");
    }

    @Test
    @DisplayName("申诉：提交 → 通过 → 考勤变 PRESENT（决策 A 下的无记录申诉）")
    void appealApprovedBecomesPresent() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        long cid = post("/api/v1/admin/classes",
                Map.of("code", "AC" + s, "name", "申诉班" + s), admin)
                .getBody().path("data").path("id").asLong();
        long kid = post("/api/v1/admin/courses",
                Map.of("code", "AK" + s.toUpperCase(), "name", "申诉课"), admin)
                .getBody().path("data").path("id").asLong();
        long tid = post("/api/v1/admin/teachers",
                Map.of("teacherNo", "AT" + s, "name", "申诉教师", "password", "Teacher12345"), admin)
                .getBody().path("data").path("id").asLong();
        for (String[] p : new String[][]{{"CLASS", String.valueOf(cid)}, {"COURSE", String.valueOf(kid)}}) {
            post("/api/v1/admin/teacher-scopes",
                    Map.of("teacherId", tid, "scopeType", p[0], "scopeId", Long.parseLong(p[1])), admin);
        }
        post("/api/v1/admin/students", Map.of("studentNo", "AS" + s, "name", "申诉生", "classId", cid), admin);
        post("/api/v1/auth/student/activate", Map.of("studentNo", "AS" + s, "name", "申诉生",
                "classCode", "AC" + s, "password", "Student12345"), null);
        String stu = login("AS" + s, "Student12345");
        String teacher = login("AT" + s, "Teacher12345");

        long sid = post("/api/v1/admin/sessions",
                Map.of("classId", cid, "courseId", kid, "title", "申诉签到", "durationMinutes", 30), teacher)
                .getBody().path("data").path("id").asLong();
        post("/api/v1/admin/sessions/" + sid + "/close", null, teacher);

        ResponseEntity<JsonNode> created = post("/api/v1/mobile/appeals",
                Map.of("sessionId", sid, "reason", "设备故障导致未能签到"), stu);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        long appealId = created.getBody().path("data").path("id").asLong();

        ResponseEntity<JsonNode> handled = post("/api/v1/admin/appeals/" + appealId + "/handle",
                Map.of("approved", true, "reply", "已核实，予以通过"), teacher);
        assertThat(handled.getStatusCode().value()).isEqualTo(200);
        assertThat(handled.getBody().path("data").path("status").asText()).isEqualTo("APPROVED");

        JsonNode att = get("/api/v1/mobile/attendance/" + sid, stu).getBody().path("data");
        assertThat(att.path("status").asText()).as("申诉通过后变 PRESENT").isEqualTo("PRESENT");
    }
}
