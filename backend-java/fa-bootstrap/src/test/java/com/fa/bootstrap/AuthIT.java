package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * B2 认证链路验收（项目文档 WP-02）。
 *
 * <p>覆盖：登录成功、错误密码第 6 次锁定、refresh 换新、access 冒充 refresh 被拒、
 * 登出后令牌进黑名单。
 */
class AuthIT extends ItBase {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PWD = "Admin@12345";

    @Test
    @DisplayName("登录成功，返回双令牌与 profile")
    void loginOk() {
        ResponseEntity<JsonNode> resp = post("/api/v1/auth/login",
                Map.of("username", ADMIN, "password", ADMIN_PWD), null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(code(resp)).isEqualTo("FA-0000");
        JsonNode data = resp.getBody().path("data");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();
        assertThat(data.path("role").asText()).isEqualTo("ADMIN");
        assertThat(data.path("profile").path("role").asText()).isEqualTo("ADMIN");
        // traceId 由 ResponseBodyAdvice 注入
        assertThat(resp.getBody().path("traceId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("错误密码：前 5 次 FA-1001，第 6 次提示锁定")
    void lockAfterFiveFailures() {
        String username = "nobody-" + suffix();
        for (int i = 0; i < 5; i++) {
            ResponseEntity<JsonNode> r = post("/api/v1/auth/login",
                    Map.of("username", username, "password", "WrongPass123"), null);
            assertThat(code(r)).isEqualTo("FA-1001");
        }
        ResponseEntity<JsonNode> sixth = post("/api/v1/auth/login",
                Map.of("username", username, "password", "WrongPass123"), null);
        assertThat(code(sixth)).isEqualTo("FA-1001");
        assertThat(sixth.getBody().path("message").asText()).contains("次数过多");
    }

    @Test
    @DisplayName("refresh 能换到新的 access；access 冒充 refresh 被拒")
    void refreshAndTypeCheck() {
        ResponseEntity<JsonNode> login = post("/api/v1/auth/login",
                Map.of("username", ADMIN, "password", ADMIN_PWD), null);
        String access = login.getBody().path("data").path("accessToken").asText();
        String refresh = login.getBody().path("data").path("refreshToken").asText();

        ResponseEntity<JsonNode> ok = post("/api/v1/auth/refresh",
                Map.of("refreshToken", refresh), null);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody().path("data").path("accessToken").asText()).isNotBlank();

        ResponseEntity<JsonNode> bad = post("/api/v1/auth/refresh",
                Map.of("refreshToken", access), null);
        assertThat(code(bad)).isEqualTo("FA-1002");
    }

    @Test
    @DisplayName("登出后同一令牌访问 /me 返回 FA-1002")
    void logoutRevokes() {
        String token = login(ADMIN, ADMIN_PWD);
        assertThat(get("/api/v1/auth/me", token).getStatusCode().value()).isEqualTo(200);

        post("/api/v1/auth/logout", null, token);

        ResponseEntity<JsonNode> after = get("/api/v1/auth/me", token);
        assertThat(after.getStatusCode().value()).isEqualTo(401);
        assertThat(code(after)).isEqualTo("FA-1002");
    }

    @Test
    @DisplayName("未带令牌访问受保护接口返回 FA-1002，而不是 FA-1003")
    void anonymousGets1002() {
        ResponseEntity<JsonNode> resp = get("/api/v1/auth/me", null);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(code(resp)).isEqualTo("FA-1002");
    }
}
