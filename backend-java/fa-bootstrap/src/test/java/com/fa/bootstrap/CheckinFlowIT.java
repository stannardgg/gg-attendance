package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B4 签到主链路（把本地 21 项冒烟转成 CI 用例）。
 *
 * <p>只保留**回归价值高**的判据：快照应到、进行中列表、无码进入、
 * 人脸档案硬门禁、异班拒绝、commit 幂等、相似度下限、结束后不可 join。
 * UI 细节不在这里测。
 */
class CheckinFlowIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";
    private static final String INTERNAL = "dev-internal-token";

    @Autowired
    private JdbcTemplate jdbc;

    private ResponseEntity<JsonNode> internalPost(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL);
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> internalGet(String path) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
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

        // 无二维码：学生端直接列出本班进行中的签到
        post("/api/v1/auth/student/activate", Map.of("studentNo", "FS" + s + "1", "name", "学生1",
                "classCode", "FC" + s, "password", "Student12345"), null);
        post("/api/v1/auth/student/activate", Map.of("studentNo", "FS" + s + "2", "name", "学生2",
                "classCode", "FC" + s, "password", "Student12345"), null);
        String stu = login("FS" + s + "1", "Student12345");
        String stu2 = login("FS" + s + "2", "Student12345");

        ResponseEntity<JsonNode> active = get("/api/v1/mobile/sessions/active", stu);
        assertThat(active.getStatusCode().value()).isEqualTo(200);
        JsonNode activeList = active.getBody().path("data");
        assertThat(activeList.isArray()).as("active 必须是数组").isTrue();
        assertThat(activeList.findValuesAsText("sessionId")).contains(String.valueOf(sid));

        // 只保留刷脸：没有人脸档案 → FA-4006，且不签发票据
        ResponseEntity<JsonNode> noFace = post("/api/v1/mobile/sessions/" + sid + "/join", null, stu2);
        assertThat(noFace.getStatusCode().value()).isEqualTo(400);
        assertThat(code(noFace)).isEqualTo("FA-4006");

        // 人脸档案 ACTIVE 后才放行（本 IT 不跑真实采集，直接置状态）
        // 注意：建学生时 StudentService 已自动插入 face_profile 行，这里只能 UPDATE，
        // 再 INSERT 会撞 uk_face_profile_student（CI 上踩过）。
        int touched = jdbc.update("UPDATE face_profile SET status = 'ACTIVE', sample_count = 5 "
                + "WHERE student_id = ?", s1);
        assertThat(touched).as("学生创建时应已存在 face_profile 行").isEqualTo(1);

        // 学生 join：无请求体、无 token，身份完全来自 JWT
        ResponseEntity<JsonNode> join = post("/api/v1/mobile/sessions/" + sid + "/join", null, stu);
        assertThat(join.getStatusCode().value()).isEqualTo(200);
        assertThat(join.getBody().path("data").path("checkinTicket").asText()).isNotBlank();
        assertThat(join.getBody().path("data").path("challengeRequired").asBoolean()).isTrue();

        // commit
        Map<String, Object> body = Map.of(
                "sessionId", sid, "studentId", s1, "similarity", 0.9123,
                "livenessMedian", 0.99, "identitySim", 0.97,
                "actionCount", 1, "actionSeq", "MOVE_CLOSER");
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
        ResponseEntity<JsonNode> afterClose = post("/api/v1/mobile/sessions/" + sid + "/join", null, stu);
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

    @Test
    @DisplayName("进行中申诉：提交后本场不可再参与（join/commit 均 FA-5001，首页标申诉中）")
    void appealWhileActiveBlocksCheckin() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        long cid = post("/api/v1/admin/classes",
                Map.of("code", "WC" + s, "name", "进行中申诉班" + s), admin)
                .getBody().path("data").path("id").asLong();
        long kid = post("/api/v1/admin/courses",
                Map.of("code", "WK" + s.toUpperCase(), "name", "进行中申诉课"), admin)
                .getBody().path("data").path("id").asLong();
        long tid = post("/api/v1/admin/teachers",
                Map.of("teacherNo", "WT" + s, "name", "进行中申诉教师", "password", "Teacher12345"), admin)
                .getBody().path("data").path("id").asLong();
        for (String[] p : new String[][]{{"CLASS", String.valueOf(cid)}, {"COURSE", String.valueOf(kid)}}) {
            post("/api/v1/admin/teacher-scopes",
                    Map.of("teacherId", tid, "scopeType", p[0], "scopeId", Long.parseLong(p[1])), admin);
        }
        long stuId = post("/api/v1/admin/students",
                Map.of("studentNo", "WS" + s, "name", "进行中申诉生", "classId", cid), admin)
                .getBody().path("data").path("id").asLong();
        post("/api/v1/auth/student/activate", Map.of("studentNo", "WS" + s, "name", "进行中申诉生",
                "classCode", "WC" + s, "password", "Student12345"), null);
        String stu = login("WS" + s, "Student12345");
        String teacher = login("WT" + s, "Teacher12345");

        // 会话保持 ACTIVE：本用例要证明「签到过程中」也能申诉
        long sid = post("/api/v1/admin/sessions",
                Map.of("classId", cid, "courseId", kid, "title", "进行中申诉签到", "durationMinutes", 30),
                teacher).getBody().path("data").path("id").asLong();
        assertThat(jdbc.update("UPDATE face_profile SET status = 'ACTIVE', sample_count = 5 "
                        + "WHERE student_id = ?", stuId))
                .as("学生创建时应已存在 face_profile 行").isEqualTo(1);

        // 先确认正常情况下换得到票
        assertThat(post("/api/v1/mobile/sessions/" + sid + "/join", null, stu)
                .getStatusCode().value()).isEqualTo(200);

        // 进行中提交申诉：没有 attendance 行也应成功（attendanceId=0）
        ResponseEntity<JsonNode> created = post("/api/v1/mobile/appeals",
                Map.of("sessionId", sid, "reason", "刷脸一直提示不匹配，我就在教室里"), stu);
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        assertThat(created.getBody().path("data").path("status").asText()).isEqualTo("PENDING");

        // ① join 拒绝，文案就是学生看到的那句
        ResponseEntity<JsonNode> rejoin = post("/api/v1/mobile/sessions/" + sid + "/join", null, stu);
        assertThat(rejoin.getStatusCode().value()).isEqualTo(409);
        assertThat(code(rejoin)).isEqualTo("FA-5001");
        assertThat(rejoin.getBody().path("message").asText()).isEqualTo("你已提交本次申诉");

        // ② commit 兜底：先领过票、再在别处申诉的路径也堵上
        ResponseEntity<JsonNode> commit = internalPost("/internal/checkin/commit",
                Map.of("sessionId", sid, "studentId", stuId, "similarity", 0.91, "actionCount", 1));
        assertThat(commit.getStatusCode().value()).isEqualTo(409);
        assertThat(code(commit)).isEqualTo("FA-5001");

        // ③ 首页列表带上申诉状态，前端据此把「待签到」改成「申诉中」
        JsonNode mine = null;
        for (JsonNode it : get("/api/v1/mobile/sessions/active", stu).getBody().path("data")) {
            if (it.path("sessionId").asLong() == sid) {
                mine = it;
            }
        }
        assertThat(mine).as("进行中的场次仍应出现在列表里").isNotNull();
        assertThat(mine.path("appealStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("CR-023：识别失败 3 次后 join 以 FA-3005 拒绝，active 回传 retryLeft=0")
    void retryExceededBlocksJoin() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        long cid = post("/api/v1/admin/classes",
                Map.of("code", "RC" + s, "name", "限次班" + s), admin)
                .getBody().path("data").path("id").asLong();
        long kid = post("/api/v1/admin/courses",
                Map.of("code", "RK" + s.toUpperCase(), "name", "限次课"), admin)
                .getBody().path("data").path("id").asLong();
        long tid = post("/api/v1/admin/teachers",
                Map.of("teacherNo", "RT" + s, "name", "限次教师", "password", "Teacher12345"), admin)
                .getBody().path("data").path("id").asLong();
        for (String[] p : new String[][]{{"CLASS", String.valueOf(cid)}, {"COURSE", String.valueOf(kid)}}) {
            post("/api/v1/admin/teacher-scopes",
                    Map.of("teacherId", tid, "scopeType", p[0], "scopeId", Long.parseLong(p[1])), admin);
        }
        long stuId = post("/api/v1/admin/students",
                Map.of("studentNo", "RS" + s, "name", "限次生", "classId", cid), admin)
                .getBody().path("data").path("id").asLong();
        post("/api/v1/auth/student/activate", Map.of("studentNo", "RS" + s, "name", "限次生",
                "classCode", "RC" + s, "password", "Student12345"), null);
        String stu = login("RS" + s, "Student12345");
        String teacher = login("RT" + s, "Teacher12345");

        long sid = post("/api/v1/admin/sessions",
                Map.of("classId", cid, "courseId", kid, "title", "限次签到", "durationMinutes", 30),
                teacher).getBody().path("data").path("id").asLong();
        assertThat(jdbc.update("UPDATE face_profile SET status = 'ACTIVE', sample_count = 5 "
                        + "WHERE student_id = ?", stuId))
                .as("学生创建时应已存在 face_profile 行").isEqualTo(1);

        // 首次 join：retryLeft=3（上限，无失败记录）
        ResponseEntity<JsonNode> join = post("/api/v1/mobile/sessions/" + sid + "/join", null, stu);
        assertThat(join.getStatusCode().value()).isEqualTo(200);
        assertThat(join.getBody().path("data").path("retryLeft").asInt())
                .as("JoinData.retryLeft 应为真实剩余次数，不再硬编码").isEqualTo(3);

        // 三次失败上报（Python finish 判失败后带 rejectGate 调这里）
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<JsonNode> fail = internalPost("/internal/checkin/attempt/fail",
                    Map.of("sessionId", sid, "studentId", stuId, "rejectGate", "action_count"));
            assertThat(fail.getStatusCode().value()).as("第 %d 次上报", i).isEqualTo(200);
            JsonNode attempt = fail.getBody().path("data");
            assertThat(attempt.path("failCount").asInt()).isEqualTo(i);
            assertThat(attempt.path("retryLeft").asInt()).isEqualTo(3 - i);
            assertThat(attempt.path("blocked").asBoolean()).isEqualTo(i == 3);
        }

        // 第 4 次领票：权威拒绝，429 FA-3005（采集页的 UI 隐藏只是第一道）
        ResponseEntity<JsonNode> blocked = post("/api/v1/mobile/sessions/" + sid + "/join", null, stu);
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(code(blocked)).isEqualTo("FA-3005");

        // 首页列表回传 retryLeft=0：前端据此显示「识别失败」并拒绝点击
        JsonNode mine = null;
        for (JsonNode it : get("/api/v1/mobile/sessions/active", stu).getBody().path("data")) {
            if (it.path("sessionId").asLong() == sid) {
                mine = it;
            }
        }
        assertThat(mine).as("进行中的场次仍应出现在列表里").isNotNull();
        assertThat(mine.path("retryLeft").asInt()).isEqualTo(0);

        // 内部查询端点（Python start 前调用）：blocked=true，旧票据偷跑第 4 次被拦
        ResponseEntity<JsonNode> status = internalGet(
                "/internal/checkin/attempt?sessionId=" + sid + "&studentId=" + stuId);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        assertThat(status.getBody().path("data").path("blocked").asBoolean()).isTrue();
        assertThat(status.getBody().path("data").path("failCount").asInt()).isEqualTo(3);
    }
}
