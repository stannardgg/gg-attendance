package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * B5 · Agent 写操作五道闸（§11.5）。
 *
 * <p>这条链路是本批次唯一的「机器能写业务数据」的入口，因此把防错全部固化成断言：
 * 预览不落库、确认才执行、令牌一次性、参数篡改被拒、断点重放幂等、撤销可回滚。
 *
 * <p>为什么必须走真实 HTTP 而不是单测：令牌存在 Redis、留痕在 MySQL、
 * 权限来自 teacher_scope——这三样都只有跑起来才能验证它们串在一起是对的。
 */
class AgentIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";
    private static final String INTERNAL = "dev-internal-token";

    /** 内部端点要两个头：X-Internal-Token 证明「调用方是自家服务」，X-User-Token 证明「代表谁」。 */
    private ResponseEntity<JsonNode> tool(String tool, Map<String, Object> params,
                                         String confirmToken, String callId, String userToken) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL);
        h.set("X-User-Token", userToken);
        h.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("params", params);
        if (confirmToken != null) {
            body.put("confirmToken", confirmToken);
        }
        if (callId != null) {
            body.put("toolCallId", callId);
        }
        return rest.exchange("/internal/agent/tools/" + tool, HttpMethod.POST,
                new HttpEntity<>(body, h), JsonNode.class);
    }

    private ResponseEntity<JsonNode> undo(long actionLogId, String userToken) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL);
        h.set("X-User-Token", userToken);
        return rest.exchange("/internal/agent/actions/" + actionLogId + "/undo", HttpMethod.POST,
                new HttpEntity<>(null, h), JsonNode.class);
    }

    @Test
    @DisplayName("Gate：预览→篡改被拒→令牌一次性→确认执行→幂等重放→撤销→不可重复撤销")
    void writeFlow() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();
        String callId = UUID.randomUUID().toString();
        Map<String, Object> params = Map.of("code", "AG" + s, "name", "Agent班级" + s);

        // 1) 预览：给卡片和令牌，但不写库
        ResponseEntity<JsonNode> preview = tool("create_class", params, null, callId, admin);
        assertThat(code(preview)).isEqualTo("FA-0000");
        JsonNode pd = preview.getBody().path("data");
        assertThat(pd.path("requiresConfirm").asBoolean()).isTrue();
        assertThat(pd.path("preview").path("affectedCount").asInt()).isEqualTo(1);
        String token = pd.path("confirmToken").asText();
        assertThat(token).isNotBlank();

        assertThat(get("/api/v1/admin/classes?page=1&size=200&keyword=AG" + s, admin).getBody()
                .path("data").path("total").asInt())
                .as("预览不得写库")
                .isZero();

        // 2) 确认 A 执行 B：paramsHash 对不上 → FA-6003
        ResponseEntity<JsonNode> tampered = tool("create_class",
                Map.of("code", "AZ" + s, "name", "Agent班级" + s), token, callId, admin);
        assertThat(code(tampered)).isEqualTo("FA-6003");

        // 3) 令牌已被上一次请求取走（GETDEL），即使参数改回原样也不能再用
        assertThat(code(tool("create_class", params, token, callId, admin))).isEqualTo("FA-6002");

        // 4) 重新预览 + 执行
        String token2 = tool("create_class", params, null, callId, admin)
                .getBody().path("data").path("confirmToken").asText();
        ResponseEntity<JsonNode> commit = tool("create_class", params, token2, callId, admin);
        assertThat(code(commit)).isEqualTo("FA-0000");
        long logId = commit.getBody().path("data").path("actionLogId").asLong();
        assertThat(logId).isPositive();

        // 5) 同一 toolCallId 断点重放：返回上次结果，不重复建班
        ResponseEntity<JsonNode> replay = tool("create_class", params, token2, callId, admin);
        assertThat(code(replay)).isEqualTo("FA-0000");
        assertThat(replay.getBody().path("data").path("actionLogId").asLong())
                .as("重放必须命中幂等缓存，而不是又建了一个班")
                .isEqualTo(logId);

        // 6) 撤销：班级被归档（不物理删除，历史考勤仍可追溯）
        ResponseEntity<JsonNode> undone = undo(logId, admin);
        assertThat(code(undone)).isEqualTo("FA-0000");
        assertThat(undone.getBody().path("data").path("undone").asBoolean()).isTrue();

        JsonNode archived = get("/api/v1/admin/classes?page=1&size=200&keyword=AG" + s, admin)
                .getBody().path("data").path("items");
        assertThat(archived.isArray()).isTrue();
        assertThat(archived.size()).as("归档后默认列表不再返回").isZero();

        JsonNode withArchived = get(
                "/api/v1/admin/classes?page=1&size=200&keyword=AG" + s + "&status=ALL", admin)
                .getBody().path("data").path("items");
        assertThat(withArchived.size()).isEqualTo(1);
        assertThat(withArchived.get(0).path("status").asText()).isEqualTo("ARCHIVED");

        // 7) 再撤一次 → FA-6005
        assertThat(code(undo(logId, admin))).isEqualTo("FA-6005");
    }

    @Test
    @DisplayName("catalog 按级别下发，L3 默认关闭；缺内部令牌一律 401")
    void catalog() {
        String admin = login(ADMIN, ADMIN_PWD);
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL);
        h.set("X-User-Token", admin);
        ResponseEntity<JsonNode> resp = rest.exchange("/internal/agent/tools/catalog?userId=1",
                HttpMethod.GET, new HttpEntity<>(null, h), JsonNode.class);
        assertThat(code(resp)).isEqualTo("FA-0000");
        assertThat(resp.getBody().path("data").path("level").path("L2").asBoolean()).isTrue();
        assertThat(resp.getBody().path("data").path("level").path("L3").asBoolean())
                .as("L3 高危能力默认关闭（sys_config 未配置时取默认值）")
                .isFalse();
        assertThat(resp.getBody().path("data").path("tools").size()).isGreaterThan(20);
        // 身份随 catalog 每轮下发：Agent 据此把负责教师落为本人（教师）/要求显式 ID（管理员）
        assertThat(resp.getBody().path("data").path("identity").path("role").asText()).isEqualTo("ADMIN");
        assertThat(resp.getBody().path("data").path("identity").path("teacherId").isNull()).isTrue();

        HttpHeaders noToken = new HttpHeaders();
        noToken.set("X-User-Token", admin);
        assertThat(rest.exchange("/internal/agent/tools/catalog?userId=1", HttpMethod.GET,
                new HttpEntity<>(null, noToken), JsonNode.class).getStatusCode().value())
                .as("内部端点不能只靠网络隔离")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("教师改范围外的班级 → FA-6004；Agent 不放宽也不收紧 HTTP 权限")
    void teacherDenied() {
        String admin = login(ADMIN, ADMIN_PWD);
        String s = suffix();

        // 管理端本来就允许 TEACHER 建班（AdminClassController 是 hasAnyRole('TEACHER','ADMIN')），
        // Agent 侧必须保持一致：权限只做「范围」收缩，不做「角色」加码。
        long classId = post("/api/v1/admin/classes",
                Map.of("code", "AY" + s, "name", "范围外班级" + s), admin)
                .getBody().path("data").path("id").asLong();

        String teacherNo = "AT" + s;
        post("/api/v1/admin/teachers",
                Map.of("teacherNo", teacherNo, "name", "Agent教师", "password", "Teacher12345"), admin);
        String teacher = login(teacherNo, "Teacher12345");

        // 教师的 catalog 必须带 identity.teacherId——Agent 靠它把负责人落为本人
        HttpHeaders th = new HttpHeaders();
        th.set("X-Internal-Token", INTERNAL);
        th.set("X-User-Token", teacher);
        ResponseEntity<JsonNode> tcat = rest.exchange("/internal/agent/tools/catalog?userId=1",
                HttpMethod.GET, new HttpEntity<>(null, th), JsonNode.class);
        assertThat(code(tcat)).isEqualTo("FA-0000");
        assertThat(tcat.getBody().path("data").path("identity").path("role").asText()).isEqualTo("TEACHER");
        assertThat(tcat.getBody().path("data").path("identity").path("teacherId").asLong()).isPositive();

        // 这个班不在该教师的 teacher_scope 里，写操作必须被挡
        ResponseEntity<JsonNode> resp = tool("update_class",
                Map.of("classId", classId, "name", "被越权改的名字"),
                null, UUID.randomUUID().toString(), teacher);
        assertThat(code(resp)).isEqualTo("FA-6004");
        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }
}
