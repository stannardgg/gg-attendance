package com.fa.org.api;

import java.sql.Blob;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人脸向量快照（CR-002，2026-11-19 批准）。
 *
 * <p>放在 fa-org 而不是独立模块：face_vector 与 student 都是组织域数据，
 * 读取它们的条件（学生必须 ACTIVE）也是业务规则，不该散到别处。
 *
 * <p><b>只读</b>。写入 face_vector 的是 Java 的审核流程（WP-08 之后的 enroll 落库），
 * 这里只做 SELECT，不提供任何写端点——单一写入口原则在内部接口里同样成立。
 *
 * <p>返回的是**全量快照**而不是增量游标：删除传播靠下一次替换天然覆盖，
 * 快照对 Python 侧的状态漂移还有自愈作用。1000 名学生约 1MB / 60 秒，可接受。
 */
@RestController
public class InternalVectorController {

    private final JdbcTemplate jdbc;

    public InternalVectorController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/internal/face/vectors")
    public Map<String, Object> snapshot() {
        // JOIN student 且 status=ACTIVE：学生被删除或归档后，向量自然从快照里消失。
        // 这一条 join 是业务规则，漏掉的话被归档学生仍然能被 1:N 认出来。
        //
        // ★ 用 ResultSetExtractor（lambda 返回值）而不是 RowCallbackHandler（void）：
        //   两者的 lambda 写法几乎一样，但语义不同——RowCallbackHandler 是逐行回调、
        //   游标停在当前行；按「while(rs.next())」写会把自己唯一的行跳过。
        //   这正是第一次跑就 count=0 的原因。
        List<Map<String, Object>> vectors = jdbc.query("""
                SELECT fv.student_id, fv.dim, fv.quality_score, fv.embedding
                FROM face_vector fv
                JOIN student s ON s.id = fv.student_id
                WHERE s.status = 'ACTIVE'
                """,
                rs -> {
                    List<Map<String, Object>> list = new ArrayList<>();
                    while (rs.next()) {
                        Blob blob = rs.getBlob("embedding");
                        byte[] bytes = blob.getBytes(1, (int) blob.length());
                        list.add(Map.of(
                                "studentId", rs.getLong("student_id"),
                                "vector", Base64.getEncoder().encodeToString(bytes),
                                "dim", rs.getInt("dim"),
                                "qualityScore", rs.getDouble("quality_score")));
                    }
                    return list;
                });
        return Map.of(
                "count", vectors.size(),
                "serverTime", java.time.OffsetDateTime.now().toString(),
                "vectors", vectors);
    }
}
