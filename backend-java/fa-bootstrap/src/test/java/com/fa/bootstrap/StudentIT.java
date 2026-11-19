package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
}
