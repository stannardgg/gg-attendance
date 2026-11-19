package com.fa.agent.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * paramsHash 的规范化测试。
 *
 * <p>核心风险：如果哈希对键顺序敏感，Agent「原样重放」会被判成「参数已篡改」，
 * 用户点了确认却永远执行不了。这条必须有测试守着。
 */
class ConfirmTokenServiceTest {

    private final ConfirmTokenService service =
            new ConfirmTokenService(null, new ObjectMapper(), 60);

    @Test
    @DisplayName("键顺序不同但内容相同 → 哈希相同")
    void hashIsOrderInsensitive() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("classId", 2);
        a.put("courseId", 7);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("courseId", 7);
        b.put("classId", 2);
        assertEquals(service.paramsHash(a), service.paramsHash(b));
    }

    @Test
    @DisplayName("改一个值 → 哈希必须变（防「确认 A 执行 B」）")
    void hashChangesWhenValueChanges() {
        assertNotEquals(
                service.paramsHash(Map.of("classId", 2)),
                service.paramsHash(Map.of("classId", 3)));
    }

    @Test
    @DisplayName("嵌套对象的键顺序同样不影响哈希")
    void hashIsOrderInsensitiveForNested() {
        Map<String, Object> inner1 = new LinkedHashMap<>();
        inner1.put("x", 1);
        inner1.put("y", 2);
        Map<String, Object> inner2 = new LinkedHashMap<>();
        inner2.put("y", 2);
        inner2.put("x", 1);
        assertEquals(service.paramsHash(Map.of("outer", inner1)),
                service.paramsHash(Map.of("outer", inner2)));
    }

    @Test
    @DisplayName("令牌指纹是 64 位十六进制，且同一令牌稳定")
    void fingerprintStable() {
        String fp = ConfirmTokenService.fingerprint("abc");
        assertEquals(64, fp.length());
        assertEquals(fp, ConfirmTokenService.fingerprint("abc"));
    }
}
