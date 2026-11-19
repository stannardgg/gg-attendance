package com.fa.agent.app.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fa.common.error.BizException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 入参校验的单元测试。
 *
 * <p>这三条不是"顺手补的覆盖率"，每一条都对应一个真实的攻击/误用路径：
 * 未知字段（模型被诱导塞 confirmToken）、缺必填（模型漏参）、枚举越界（脏数据）。
 */
class ParamsTest {

    private static final Map<String, Object> SCHEMA = ToolSpec.object(Map.of(
            "sessionId", ToolSpec.int64("会话"),
            "status", ToolSpec.enums("状态", List.of("PRESENT", "LATE")),
            "reason", ToolSpec.str("原因")),
            List.of("sessionId", "reason"));

    @Test
    @DisplayName("未知字段直接拒绝——这是「往 params 里塞 confirmToken」的拦截点")
    void rejectsUnknownField() {
        BizException e = assertThrows(BizException.class, () -> Params.of(
                Map.of("sessionId", 1, "reason", "x", "confirmToken", "forged"), SCHEMA));
        assertEquals("FA-2001", e.errorCode().code());
        assertEquals(true, e.getMessage().contains("confirmToken"));
    }

    @Test
    @DisplayName("缺必填字段报 FA-2001 且指出字段名")
    void rejectsMissingRequired() {
        BizException e = assertThrows(BizException.class,
                () -> Params.of(Map.of("sessionId", 1), SCHEMA));
        assertEquals(true, e.getMessage().contains("reason"));
    }

    @Test
    @DisplayName("枚举越界拒绝，不落到 SQL")
    void rejectsEnumOutOfRange() {
        assertThrows(BizException.class, () -> Params.of(
                Map.of("sessionId", 1, "reason", "x", "status", "DONE"), SCHEMA));
    }

    @Test
    @DisplayName("字符串数字可以转成 Long，避免 ClassCastException 变 500")
    void coercesNumericString() {
        Params p = Params.of(Map.of("sessionId", "42", "reason", "x"), SCHEMA);
        assertEquals(42L, p.requireLng("sessionId"));
    }
}
