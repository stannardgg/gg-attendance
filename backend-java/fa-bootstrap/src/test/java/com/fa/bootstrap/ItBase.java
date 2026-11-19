package com.fa.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 集成测试基类。
 *
 * <p>需要真实的 MySQL + Redis，所以只在 CI 跑（{@code mvn -Pintegration verify}）。
 * 本机 docker run 不可用，这条链路的验证就交给 CI——和 B1 处理 flyway 的思路一致。
 *
 * <p>每个用例自己造数据、并用随机后缀避免互相干扰：CI 里同一个库会被多个测试类共用，
 * 依赖「执行顺序」的测试是有毒的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ItBase {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper mapper;

    protected static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 登录并返回 accessToken。失败会直接把断言打出来，避免后续 NPE 掩盖真正原因。 */
    protected String login(String username, String password) {
        ResponseEntity<JsonNode> resp = post("/api/v1/auth/login",
                Map.of("username", username, "password", password), null);
        assertThat(resp.getStatusCode().value())
                .as("login %s 应成功，实际 body=%s", username, resp.getBody())
                .isEqualTo(200);
        return resp.getBody().path("data").path("accessToken").asText();
    }

    protected ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return exchange(path, HttpMethod.POST, body, token);
    }

    protected ResponseEntity<JsonNode> put(String path, Object body, String token) {
        return exchange(path, HttpMethod.PUT, body, token);
    }

    protected ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(path, HttpMethod.GET, null, token);
    }

    protected ResponseEntity<JsonNode> delete(String path, String token) {
        return exchange(path, HttpMethod.DELETE, null, token);
    }

    protected ResponseEntity<JsonNode> exchange(String path, HttpMethod method, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return rest.exchange(path, method, entity, JsonNode.class);
    }

    protected String code(ResponseEntity<JsonNode> resp) {
        return resp.getBody() == null ? null : resp.getBody().path("code").asText();
    }

    protected List<JsonNode> items(ResponseEntity<JsonNode> resp) {
        JsonNode arr = resp.getBody().path("data").path("items");
        assertThat(arr.isArray()).as("响应 data.items 应为数组，实际：%s", resp.getBody()).isTrue();
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false).toList();
    }
}
