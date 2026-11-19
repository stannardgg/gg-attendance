package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
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
 * CR-002：向量快照端点（GET /internal/face/vectors）。
 *
 * 覆盖：无令牌 401、令牌正确返回快照、学生删除后从快照消失。
 * 注意 CI 的 INTERNAL_TOKEN 默认 dev-internal-token（两侧默认一致）。
 */
class VectorSyncIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";
    private static final String TOKEN = "dev-internal-token";

    @Autowired
    private JdbcTemplate jdbc;

    private long createActiveStudent(String admin, String s) {
        long classId;
        ResponseEntity<JsonNode> cr = post("/api/v1/admin/classes",
                Map.of("code", "V" + s, "name", "向量班 " + s), admin);
        assertThat(cr.getStatusCode().value()).isEqualTo(200);
        classId = cr.getBody().path("data").path("id").asLong();

        ResponseEntity<JsonNode> sr = post("/api/v1/admin/students",
                Map.of("studentNo", "VS" + s, "name", "向量生", "classId", classId), admin);
        assertThat(sr.getStatusCode().value()).isEqualTo(200);
        return sr.getBody().path("data").path("id").asLong();
    }

    private byte[] normalized512() {
        ByteBuffer buf = ByteBuffer.allocate(512 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 512; i++) {
            buf.putFloat(i == 0 ? 1f : 0f);
        }
        return buf.array();
    }

    private ResponseEntity<JsonNode> getVectors(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("X-Internal-Token", token);
        }
        return rest.exchange("/internal/face/vectors", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    @DisplayName("无令牌 401；令牌正确返回快照；学生删除后从快照消失")
    void snapshotLifecycle() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        long studentId = createActiveStudent(admin, s);

        // 写入向量（模拟 enroll 落库：小端 float32，L2 归一化）
        jdbc.update("""
                INSERT INTO face_vector (student_id, model_name, dim, embedding, quality_score)
                VALUES (?, 'w600k_r50', 512, ?, 0.900)
                """, studentId, normalized512());

        // 1) 无令牌 → 401
        assertThat(getVectors(null).getStatusCode().value()).isEqualTo(401);
        // 2) 令牌错误 → 401
        assertThat(getVectors("wrong-token").getStatusCode().value()).isEqualTo(401);

        // 3) 正确令牌 → 快照里有该学生
        ResponseEntity<JsonNode> ok = getVectors(TOKEN);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        JsonNode data = ok.getBody();
        assertThat(data.path("count").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode mine = null;
        for (JsonNode item : data.path("vectors")) {
            if (item.path("studentId").asLong() == studentId) {
                mine = item;
                break;
            }
        }
        assertThat(mine).as("快照应包含新写入的向量").isNotNull();
        byte[] decoded = Base64.getDecoder().decode(mine.path("vector").asText());
        assertThat(decoded.length).isEqualTo(512 * 4);
        assertThat(mine.path("dim").asInt()).isEqualTo(512);

        // 4) 删除学生 → 快照不再包含
        ResponseEntity<JsonNode> del = delete("/api/v1/admin/students/" + studentId, admin);
        assertThat(del.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<JsonNode> after = getVectors(TOKEN);
        boolean stillThere = false;
        for (JsonNode item : after.getBody().path("vectors")) {
            if (item.path("studentId").asLong() == studentId) {
                stillThere = true;
            }
        }
        assertThat(stillThere).as("学生删除后不应再出现在快照中").isFalse();
    }
}
