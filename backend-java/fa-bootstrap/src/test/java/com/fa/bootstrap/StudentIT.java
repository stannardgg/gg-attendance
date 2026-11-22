package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fa.common.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * 学生导入与激活验收。
 *
 * <p>Gate 项：CSV 1000 行（含 5 行非法）→ 995 成功、5 行返回行号与原因，
 * <b>已成功的行不回滚</b>。
 */
class StudentIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";

    private String createClassAndGetCode(String token, String s) {
        ResponseEntity<JsonNode> r = post("/api/v1/admin/classes",
                Map.of("code", "I" + s, "name", "导入测试班 " + s), token);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody().path("data").path("code").asText();
    }

    private ResponseEntity<JsonNode> importCsv(String token, String classCode, String csv) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "students.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return rest.postForEntity("/api/v1/admin/students/import?classCode=" + classCode,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    @Test
    @DisplayName("Gate：1000 行含 5 行非法 → 995 成功、5 条错误带行号")
    void importThousandRows() {
        String token = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        String classCode = createClassAndGetCode(token, s);

        StringBuilder csv = new StringBuilder("studentNo,name\n");
        int total = 1000;
        int bad = 5;
        for (int i = 0; i < total; i++) {
            if (i < bad) {
                // 前 5 行故意留空学号
                csv.append(",坏行").append(i).append('\n');
            } else {
                csv.append("S").append(s).append('_').append(i).append(",学生").append(i).append('\n');
            }
        }

        ResponseEntity<JsonNode> resp = importCsv(token, classCode, csv.toString());
        assertThat(resp.getStatusCode().value()).as("导入失败：%s", resp.getBody()).isEqualTo(200);

        JsonNode data = resp.getBody().path("data");
        assertThat(data.path("total").asInt()).isEqualTo(total);
        assertThat(data.path("created").asInt()).isEqualTo(total - bad);
        assertThat(data.path("updated").asInt()).isZero();
        assertThat(data.path("failed").asInt()).isEqualTo(bad);
        assertThat(data.path("errors")).hasSize(bad);
        // 行号从 1 起且含表头：第 2~6 行是坏行
        assertThat(data.path("errors").get(0).path("line").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("重复导入同一份 CSV：第二次走更新而不是新增")
    void importTwiceUpdates() {
        String token = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        String classCode = createClassAndGetCode(token, s);

        String csv = "studentNo,name\nS" + s + "_1,张三\nS" + s + "_2,李四\n";
        importCsv(token, classCode, csv);
        ResponseEntity<JsonNode> second = importCsv(token, classCode, csv);

        JsonNode data = second.getBody().path("data");
        assertThat(data.path("created").asInt()).isZero();
        assertThat(data.path("updated").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("学生激活：三要素命中 → 拿到令牌；错班级码 → 统一模糊提示")
    void activate() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        String classCode = createClassAndGetCode(admin, s);

        String studentNo = "A" + s;
        ResponseEntity<JsonNode> created = post("/api/v1/admin/students",
                Map.of("studentNo", studentNo, "name", "激活生", "classId",
                        ((Number) get("/api/v1/admin/classes?keyword=I" + s, admin)
                                .getBody().path("data").path("items").get(0).path("id").asLong())),
                admin);
        assertThat(created.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> ok = post("/api/v1/auth/student/activate",
                Map.of("studentNo", studentNo, "name", "激活生",
                        "classCode", classCode, "password", "Student12345"), null);
        assertThat(ok.getStatusCode().value()).as("激活失败：%s", ok.getBody()).isEqualTo(200);
        assertThat(ok.getBody().path("data").path("role").asText()).isEqualTo("STUDENT");
        assertThat(ok.getBody().path("data").path("profile").path("faceStatus").asText())
                .isEqualTo("NONE");

        // 再激活一次 → 已激活
        ResponseEntity<JsonNode> again = post("/api/v1/auth/student/activate",
                Map.of("studentNo", studentNo, "name", "激活生",
                        "classCode", classCode, "password", "Student12345"), null);
        assertThat(code(again)).isEqualTo("FA-1004");

        // 班级码错误 → 模糊提示，不暴露是哪个字段错了
        ResponseEntity<JsonNode> wrong = post("/api/v1/auth/student/activate",
                Map.of("studentNo", studentNo, "name", "激活生",
                        "classCode", "ZZZZ" + s, "password", "Student12345"), null);
        assertThat(wrong.getBody().path("message").asText())
                .isEqualTo("学号、姓名或班级码不匹配");
    }

    /* ---------------- CR-024 学生密码修改申请 ---------------- */

    /** 建班 + 建学生 + 激活，返回学生信息（studentNo / className）。 */
    private Map<String, String> createActivatedStudent(String admin, String s) {
        String classCode = createClassAndGetCode(admin, s);
        String studentNo = "P" + s;
        ResponseEntity<JsonNode> created = post("/api/v1/admin/students",
                Map.of("studentNo", studentNo, "name", "改密生" + s, "classId",
                        ((Number) get("/api/v1/admin/classes?keyword=I" + s, admin)
                                .getBody().path("data").path("items").get(0).path("id").asLong())),
                admin);
        assertThat(created.getStatusCode().value()).as("建学生失败：%s", created.getBody()).isEqualTo(200);
        ResponseEntity<JsonNode> ok = post("/api/v1/auth/student/activate",
                Map.of("studentNo", studentNo, "name", "改密生" + s,
                        "classCode", classCode, "password", "Student12345"), null);
        assertThat(ok.getStatusCode().value()).as("激活失败：%s", ok.getBody()).isEqualTo(200);
        return Map.of("studentNo", studentNo, "className", "导入测试班 " + s);
    }

    @Test
    @DisplayName("学生申请改密 → 管理员通过 → 新密码可登录，旧密码与旧令牌立即失效")
    void studentPasswordRequestFlow() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        Map<String, String> stu = createActivatedStudent(admin, s);
        String studentNo = stu.get("studentNo");
        long studentId = get("/api/v1/admin/students?keyword=" + studentNo, admin)
                .getBody().path("data").path("items").get(0).path("id").asLong();
        String oldToken = login(studentNo, "Student12345");
        String newPassword = "NewStudent123";

        // 缺当前密码 → 409 FA-2007（刻意不是 401，别把用户踢回登录页）
        ResponseEntity<JsonNode> noCurrent = post("/api/v1/student/profile-requests",
                Map.of("newPassword", newPassword), oldToken);
        assertThat(noCurrent.getStatusCode().value()).isEqualTo(409);
        assertThat(code(noCurrent)).isEqualTo(ErrorCode.PROFILE_REQUEST_CONFLICT.code());

        // 当前密码错 → 同样 FA-2007
        ResponseEntity<JsonNode> wrongCurrent = post("/api/v1/student/profile-requests",
                Map.of("newPassword", newPassword, "currentPassword", "not-my-password"), oldToken);
        assertThat(code(wrongCurrent)).isEqualTo(ErrorCode.PROFILE_REQUEST_CONFLICT.code());

        // 提交成功：状态 PENDING，响应里只有标记，绝不能出现哈希或明文
        ResponseEntity<JsonNode> submit = post("/api/v1/student/profile-requests",
                Map.of("newPassword", newPassword, "currentPassword", "Student12345",
                        "reason", "忘记密码了"), oldToken);
        assertThat(submit.getStatusCode().value()).as("提交失败：%s", submit.getBody()).isEqualTo(200);
        assertThat(submit.getBody().path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(submit.getBody().path("data").path("passwordChange").asBoolean()).isTrue();
        assertThat(submit.getBody().toString())
                .doesNotContain("$2a$")
                .doesNotContain("newPasswordHash")
                .doesNotContain(newPassword);

        // 同一学生第二条 PENDING 被唯一键拦下 → FA-2007
        ResponseEntity<JsonNode> dup = post("/api/v1/student/profile-requests",
                Map.of("newPassword", "Another12345", "currentPassword", "Student12345"), oldToken);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo(ErrorCode.PROFILE_REQUEST_CONFLICT.code());

        // 学生不能审核，也不能看全局列表
        long requestId = submit.getBody().path("data").path("id").asLong();
        assertThat(code(post("/api/v1/admin/student-profile-requests/" + requestId + "/handle",
                Map.of("approved", true), oldToken))).isEqualTo("FA-1003");
        assertThat(code(get("/api/v1/admin/student-profile-requests", oldToken))).isEqualTo("FA-1003");

        // 管理员按学号能搜到这条申请，且带学号与班级名
        List<JsonNode> found = items(get("/api/v1/admin/student-profile-requests?status=PENDING&keyword="
                + studentNo, admin));
        assertThat(found).anyMatch(n -> n.path("studentNo").asText().equals(studentNo)
                && n.path("className").asText().equals(stu.get("className"))
                && n.path("passwordChange").asBoolean());

        // 驳回必须写理由（不通过，申请仍是 PENDING）
        ResponseEntity<JsonNode> noReason = post("/api/v1/admin/student-profile-requests/"
                + requestId + "/handle", Map.of("approved", false), admin);
        assertThat(noReason.getStatusCode().value()).isEqualTo(409);
        assertThat(code(noReason)).isEqualTo(ErrorCode.PROFILE_REQUEST_CONFLICT.code());

        // 学生撤回后，管理员再审核 → 已被处理
        assertThat(post("/api/v1/student/profile-requests/" + requestId + "/withdraw", Map.of(), oldToken)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(code(post("/api/v1/admin/student-profile-requests/" + requestId + "/handle",
                Map.of("approved", true), admin))).isEqualTo(ErrorCode.PROFILE_REQUEST_CONFLICT.code());

        // 重新提交并审核通过
        ResponseEntity<JsonNode> resubmit = post("/api/v1/student/profile-requests",
                Map.of("newPassword", newPassword, "currentPassword", "Student12345"), oldToken);
        long approveId = resubmit.getBody().path("data").path("id").asLong();
        ResponseEntity<JsonNode> approve = post("/api/v1/admin/student-profile-requests/"
                + approveId + "/handle", Map.of("approved", true, "note", "已核实本人"), admin);
        assertThat(approve.getStatusCode().value()).as("审核失败：%s", approve.getBody()).isEqualTo(200);
        assertThat(approve.getBody().path("data").path("status").asText()).isEqualTo("APPROVED");

        // 凭据版本 +1：旧令牌立即失效
        assertThat(code(get("/api/v1/student/profile-requests", oldToken))).isEqualTo("FA-1002");

        // 旧密码不能再登录；新密码可以
        ResponseEntity<JsonNode> oldLogin = post("/api/v1/auth/login",
                Map.of("username", studentNo, "password", "Student12345"), null);
        assertThat(oldLogin.getStatusCode().value()).isEqualTo(401);
        assertThat(code(oldLogin)).isEqualTo("FA-1001");
        String newToken = login(studentNo, newPassword);
        assertThat(get("/api/v1/student/profile-requests", newToken).getStatusCode().value()).isEqualTo(200);

        // 审计里能查到这次通过
        List<JsonNode> logs = items(get("/api/v1/admin/audit-logs?action=STUDENT_PROFILE_APPROVE&size=100", admin));
        assertThat(logs).anyMatch(n -> n.path("targetId").asLong() == studentId);
    }
}
