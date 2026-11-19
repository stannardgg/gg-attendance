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
}
