package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * B2 组织域与数据范围验收（项目文档 WP-03）。
 *
 * <p>这是 B2 的出口 Gate 所在：教师 A 看不到教师 B 的班级/课程/学生；
 * 课程表单带 classId 被拒；删除有学生的班级被拒。
 */
class OrgIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";

    private long createClass(String adminToken, String nameSuffix) {
        ResponseEntity<JsonNode> r = post("/api/v1/admin/classes",
                Map.of("code", "C" + nameSuffix, "name", "测试班 " + nameSuffix, "grade", "2024"),
                adminToken);
        assertThat(r.getStatusCode().value()).as("建班失败：%s", r.getBody()).isEqualTo(200);
        return r.getBody().path("data").path("id").asLong();
    }

    private long createCourse(String adminToken, String nameSuffix) {
        ResponseEntity<JsonNode> r = post("/api/v1/admin/courses",
                Map.of("code", "K" + nameSuffix.toUpperCase(), "name", "测试课 " + nameSuffix),
                adminToken);
        assertThat(r.getStatusCode().value()).as("建课失败：%s", r.getBody()).isEqualTo(200);
        return r.getBody().path("data").path("id").asLong();
    }

    private Map<String, Object> createTeacher(String adminToken, String no) {
        ResponseEntity<JsonNode> r = post("/api/v1/admin/teachers",
                Map.of("teacherNo", no, "name", "教师" + no, "password", "Teacher12345"),
                adminToken);
        assertThat(r.getStatusCode().value()).as("建教师失败：%s", r.getBody()).isEqualTo(200);
        Map<String, Object> m = new HashMap<>();
        m.put("id", r.getBody().path("data").path("id").asLong());
        m.put("no", no);
        return m;
    }

    private void grant(String adminToken, long teacherId, String type, long scopeId) {
        ResponseEntity<JsonNode> r = post("/api/v1/admin/teacher-scopes",
                Map.of("teacherId", teacherId, "scopeType", type, "scopeId", scopeId), adminToken);
        assertThat(r.getStatusCode().value()).as("授权失败：%s", r.getBody()).isEqualTo(200);
    }

    @Test
    @DisplayName("Gate：教师 A 只看得到被授权的班级与课程，看不到教师 B 的")
    void teacherScopeIsolation() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();

        long classA = createClass(admin, "A" + s);
        long classB = createClass(admin, "B" + s);
        long courseA = createCourse(admin, "A" + s);
        long courseB = createCourse(admin, "B" + s);

        Map<String, Object> tA = createTeacher(admin, "TA" + s);
        Map<String, Object> tB = createTeacher(admin, "TB" + s);
        grant(admin, (long) tA.get("id"), "CLASS", classA);
        grant(admin, (long) tA.get("id"), "COURSE", courseA);
        grant(admin, (long) tB.get("id"), "CLASS", classB);

        String tokenA = login((String) tA.get("no"), "Teacher12345");

        List<JsonNode> classes = items(get("/api/v1/admin/classes?size=100", tokenA));
        List<Long> classIds = classes.stream().map(n -> n.path("id").asLong()).toList();
        assertThat(classIds).contains(classA).doesNotContain(classB);

        List<JsonNode> courses = items(get("/api/v1/admin/courses?size=100", tokenA));
        List<Long> courseIds = courses.stream().map(n -> n.path("id").asLong()).toList();
        assertThat(courseIds).contains(courseA).doesNotContain(courseB);

        // 直接按 id 访问未授权班级 → FA-2006，而不是 404（404 会泄露"存在但不可见"之外的猜测空间）
        ResponseEntity<JsonNode> denied = get("/api/v1/admin/classes/" + classB, tokenA);
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(code(denied)).isEqualTo("FA-2006");
    }

    @Test
    @DisplayName("Gate：TEACHER 调用教师管理接口一律 403 FA-1003")
    void teacherCannotManageTeachers() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        Map<String, Object> t = createTeacher(admin, "TC" + s);
        String token = login((String) t.get("no"), "Teacher12345");

        long id = (long) t.get("id");
        assertThat(code(get("/api/v1/admin/teachers", token))).isEqualTo("FA-1003");
        assertThat(code(delete("/api/v1/admin/teachers/" + id, token))).isEqualTo("FA-1003");
        assertThat(code(post("/api/v1/admin/teachers",
                Map.of("teacherNo", "TX" + s, "name", "x", "password", "Passw0rd123"), token)))
                .isEqualTo("FA-1003");
    }

    @Test
    @DisplayName("Gate：删除仍有在读学生的班级 → FA-2005 且带 studentCount")
    void deleteClassWithStudents() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        long classId = createClass(admin, "D" + s);

        for (int i = 1; i <= 3; i++) {
            ResponseEntity<JsonNode> r = post("/api/v1/admin/students",
                    Map.of("studentNo", "S" + s + i, "name", "学生" + i, "classId", classId), admin);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
        }

        ResponseEntity<JsonNode> del = delete("/api/v1/admin/classes/" + classId, admin);
        assertThat(del.getStatusCode().value()).isEqualTo(409);
        assertThat(code(del)).isEqualTo("FA-2005");
        assertThat(del.getBody().path("data").path("studentCount").asInt()).isEqualTo(3);

        // 班级仍然存在
        assertThat(get("/api/v1/admin/classes/" + classId, admin).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("Gate：给课程 DTO 传 classId 被 400 拒绝（未知字段）")
    void courseRejectsClassId() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();

        Map<String, Object> body = Map.of(
                "code", "KD" + s.toUpperCase(),
                "name", "带班级的课程 " + s,
                "classId", 1);

        ResponseEntity<JsonNode> r = post("/api/v1/admin/courses", body, admin);
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody().path("message").asText()).contains("请求体不合法");
    }

    @Test
    @DisplayName("班级 CRUD：建、改（code 不可改）、删")
    void classCrud() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        long id = createClass(admin, "E" + s);

        ResponseEntity<JsonNode> upd = put("/api/v1/admin/classes/" + id,
                Map.of("name", "改名班 " + s, "major", "软件工程"), admin);
        assertThat(upd.getStatusCode().value()).isEqualTo(200);
        assertThat(upd.getBody().path("data").path("name").asText()).isEqualTo("改名班 " + s);
        // code 未被修改
        assertThat(upd.getBody().path("data").path("code").asText()).isEqualTo("CE" + s);

        // 尝试通过 PUT 改 code：字段不在 DTO 里 → 400
        ResponseEntity<JsonNode> tryChangeCode = put("/api/v1/admin/classes/" + id,
                Map.of("code", "ZZZZ" + s), admin);
        assertThat(tryChangeCode.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<JsonNode> del = delete("/api/v1/admin/classes/" + id, admin);
        assertThat(del.getStatusCode().value()).isEqualTo(200);
        assertThat(del.getBody().path("data").path("archived").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("班级码重复 → FA-2003")
    void duplicateClassCode() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        createClass(admin, "F" + s);

        ResponseEntity<JsonNode> dup = post("/api/v1/admin/classes",
                Map.of("code", "CF" + s, "name", "另一个班 " + s), admin);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("FA-2003");
    }

    @Test
    @DisplayName("课程编码重复 → FA-2002（与班级的 FA-2003 区分开）")
    void duplicateCourseCode() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        createCourse(admin, "G" + s);

        ResponseEntity<JsonNode> dup = post("/api/v1/admin/courses",
                Map.of("code", "KG" + s.toUpperCase(), "name", "同名课"), admin);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("FA-2002");
    }

    /* ---------------- CR-019 教师资料修改申请 ---------------- */

    @Test
    @DisplayName("教师提交资料修改申请 → 管理员通过 → teacher 表生效且留审计")
    void teacherProfileRequestApprove() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        Map<String, Object> t = createTeacher(admin, "TP" + s);
        String token = login((String) t.get("no"), "Teacher12345");
        long teacherId = (long) t.get("id");

        // 教师能看到自己的档案（含院系/职称），且只读
        ResponseEntity<JsonNode> self = get("/api/v1/teacher/profile", token);
        assertThat(self.getStatusCode().value()).isEqualTo(200);
        assertThat(self.getBody().path("data").path("teacherNo").asText()).isEqualTo(t.get("no"));

        ResponseEntity<JsonNode> submit = post("/api/v1/teacher/profile-requests",
                Map.of("name", "改名" + s, "department", "信息学院", "reason", "职称晋升"), token);
        assertThat(submit.getStatusCode().value()).as("提交失败：%s", submit.getBody()).isEqualTo(200);
        assertThat(submit.getBody().path("data").path("status").asText()).isEqualTo("PENDING");
        long requestId = submit.getBody().path("data").path("id").asLong();

        // 同一教师第二条 PENDING 被唯一键拦下 → FA-2007
        ResponseEntity<JsonNode> dup = post("/api/v1/teacher/profile-requests",
                Map.of("title", "副教授"), token);
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        assertThat(code(dup)).isEqualTo("FA-2007");

        // 教师不能自己审核，也不能看全局列表
        assertThat(code(post("/api/v1/admin/teacher-profile-requests/" + requestId + "/handle",
                Map.of("approved", true), token))).isEqualTo("FA-1003");
        assertThat(code(get("/api/v1/admin/teacher-profile-requests", token))).isEqualTo("FA-1003");

        // 驳回必须写理由
        ResponseEntity<JsonNode> noReason = post("/api/v1/admin/teacher-profile-requests/" + requestId + "/handle",
                Map.of("approved", false), admin);
        assertThat(noReason.getStatusCode().value()).isEqualTo(409);
        assertThat(code(noReason)).isEqualTo("FA-2007");

        ResponseEntity<JsonNode> approve = post("/api/v1/admin/teacher-profile-requests/" + requestId + "/handle",
                Map.of("approved", true, "note", "核对无误"), admin);
        assertThat(approve.getStatusCode().value()).as("审核失败：%s", approve.getBody()).isEqualTo(200);
        assertThat(approve.getBody().path("data").path("status").asText()).isEqualTo("APPROVED");

        // 生效：教师档案已更新；重复审核被拒
        ResponseEntity<JsonNode> after = get("/api/v1/teacher/profile", token);
        assertThat(after.getBody().path("data").path("name").asText()).isEqualTo("改名" + s);
        assertThat(after.getBody().path("data").path("department").asText()).isEqualTo("信息学院");
        assertThat(code(post("/api/v1/admin/teacher-profile-requests/" + requestId + "/handle",
                Map.of("approved", true), admin))).isEqualTo("FA-2007");

        // 审计里能查到这次通过（before/after 都在）
        List<JsonNode> logs = items(get("/api/v1/admin/audit-logs?action=TEACHER_PROFILE_APPROVE&size=100", admin));
        assertThat(logs).anyMatch(n -> n.path("targetId").asLong() == teacherId);
    }

    @Test
    @DisplayName("资料修改申请：空串清空院系，未变更直接拒绝")
    void teacherProfileRequestClearField() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        Map<String, Object> t = createTeacher(admin, "TQ" + s);
        String token = login((String) t.get("no"), "Teacher12345");
        long teacherId = (long) t.get("id");

        // 先由管理员补一个院系
        assertThat(put("/api/v1/admin/teachers/" + teacherId, Map.of("department", "数学系"), admin)
                .getStatusCode().value()).isEqualTo(200);

        // 原样提交 → 未变更 → FA-2007
        ResponseEntity<JsonNode> same = post("/api/v1/teacher/profile-requests",
                Map.of("department", "数学系"), token);
        assertThat(same.getStatusCode().value()).isEqualTo(409);
        assertThat(code(same)).isEqualTo("FA-2007");

        // 空串 = 清空
        ResponseEntity<JsonNode> clear = post("/api/v1/teacher/profile-requests",
                Map.of("department", ""), token);
        assertThat(clear.getStatusCode().value()).as("提交失败：%s", clear.getBody()).isEqualTo(200);
        long requestId = clear.getBody().path("data").path("id").asLong();
        ResponseEntity<JsonNode> handled = post("/api/v1/admin/teacher-profile-requests/" + requestId + "/handle",
                Map.of("approved", true), admin);
        assertThat(handled.getStatusCode().value()).as("审核失败：%s", handled.getBody()).isEqualTo(200);

        // 这里断言三件事，失败信息都要带响应体：这条用例在 CI 上偶发过一次失败，
        // 只报 "Expecting true but was false" 时无法区分「院系没清空」与「令牌已失效」
        // （401 由过滤器直接写响应，不经过 GlobalExceptionHandler，日志里没有痕迹）。
        ResponseEntity<JsonNode> cleared = get("/api/v1/teacher/profile", token);
        assertThat(cleared.getStatusCode().value())
                .as("读取档案失败（401 = 令牌被 cv 校验拦下）：%s", cleared.getBody())
                .isEqualTo(200);
        assertThat(cleared.getBody().path("data").path("department").isNull())
                .as("院系应被清空，实际档案：%s", cleared.getBody())
                .isTrue();
    }

    /* ---------------- CR-022 教师账号凭据修改申请 ---------------- */

    @Test
    @DisplayName("教师申请改用户名与密码 → 通过后新凭据可用，旧密码与旧令牌立即失效")
    void teacherCredentialRequestApproved() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        Map<String, Object> t = createTeacher(admin, "TW" + s);
        String oldNo = (String) t.get("no");
        String oldToken = login(oldNo, "Teacher12345");
        String newNo = "TWX" + s;

        // 缺当前密码 → 409 FA-2007（刻意不是 401，别把用户踢回登录页）
        ResponseEntity<JsonNode> noCurrent = post("/api/v1/teacher/profile-requests",
                Map.of("newPassword", "NewPassw0rd1"), oldToken);
        assertThat(noCurrent.getStatusCode().value()).isEqualTo(409);
        assertThat(code(noCurrent)).isEqualTo("FA-2007");

        // 当前密码错 → 同样 FA-2007
        ResponseEntity<JsonNode> wrongCurrent = post("/api/v1/teacher/profile-requests",
                Map.of("newPassword", "NewPassw0rd1", "currentPassword", "not-my-password"), oldToken);
        assertThat(code(wrongCurrent)).isEqualTo("FA-2007");

        ResponseEntity<JsonNode> submit = post("/api/v1/teacher/profile-requests",
                Map.of("username", newNo, "newPassword", "NewPassw0rd1",
                        "currentPassword", "Teacher12345", "reason", "工号调整并重置密码"), oldToken);
        assertThat(submit.getStatusCode().value()).as("提交失败：%s", submit.getBody()).isEqualTo(200);
        long requestId = submit.getBody().path("data").path("id").asLong();
        assertThat(submit.getBody().path("data").path("passwordChange").asBoolean()).isTrue();
        // 响应里只有标记，绝不能出现哈希或明文
        assertThat(submit.getBody().toString())
                .doesNotContain("$2a$")
                .doesNotContain("newPasswordHash")
                .doesNotContain("NewPassw0rd1");

        ResponseEntity<JsonNode> approve = post(
                "/api/v1/admin/teacher-profile-requests/" + requestId + "/handle",
                Map.of("approved", true, "note", "已核实本人"), admin);
        assertThat(approve.getStatusCode().value()).as("审核失败：%s", approve.getBody()).isEqualTo(200);
        assertThat(approve.getBody().path("data").path("newUsername").asText()).isEqualTo(newNo);

        // 凭据版本 +1：旧令牌立即失效
        assertThat(code(get("/api/v1/teacher/profile", oldToken))).isEqualTo("FA-1002");

        // 旧密码不能再登录
        ResponseEntity<JsonNode> oldLogin = post("/api/v1/auth/login",
                Map.of("username", oldNo, "password", "Teacher12345"), null);
        assertThat(oldLogin.getStatusCode().value()).isEqualTo(401);
        assertThat(code(oldLogin)).isEqualTo("FA-1001");

        // 新用户名 + 新密码可以
        String newToken = login(newNo, "NewPassw0rd1");
        assertThat(get("/api/v1/teacher/profile", newToken)
                .getBody().path("data").path("teacherNo").asText()).isEqualTo(newNo);
    }

    @Test
    @DisplayName("改用户名撞到已有账号 → 提交阶段就 FA-2007")
    void teacherUsernameTaken() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        Map<String, Object> a = createTeacher(admin, "TY" + s);
        Map<String, Object> b = createTeacher(admin, "TZ" + s);
        String token = login((String) a.get("no"), "Teacher12345");

        ResponseEntity<JsonNode> r = post("/api/v1/teacher/profile-requests",
                Map.of("username", (String) b.get("no"), "currentPassword", "Teacher12345"), token);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("FA-2007");
    }
}
