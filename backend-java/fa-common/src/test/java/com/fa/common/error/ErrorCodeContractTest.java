package com.fa.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 错误码与契约的一致性。
 *
 * <p>这条测试的意义：错误码是前端分支的依据，重复或格式不对会在运行时才炸，
 * 而且往往是在某个罕见的失败分支上——那时候没人记得改过什么。
 * 让它在编译后的第一秒就失败。
 */
class ErrorCodeContractTest {

    @Test
    @DisplayName("全部错误码形如 FA-dddd")
    void pattern() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.code()).matches("^FA-\\d{4}$");
        }
    }

    @Test
    @DisplayName("错误码不重复")
    void unique() {
        List<String> all = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("契约里的 34 个码一个不少")
    void completeness() {
        // contracts/enums/error-codes.json 的 codes 长度为 34（CR-014 新增 FA-4008）
        assertThat(ErrorCode.values()).hasSize(34);
    }

    @Test
    @DisplayName("成功码是 FA-0000 且 HTTP 200")
    void success() {
        assertThat(ErrorCode.OK.code()).isEqualTo("FA-0000");
        assertThat(ErrorCode.OK.httpStatus().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("关键映射抽查：这几个码的前端分支最依赖")
    void keyMappings() {
        assertThat(ErrorCode.BAD_CREDENTIALS.code()).isEqualTo("FA-1001");
        assertThat(ErrorCode.BAD_CREDENTIALS.httpStatus().value()).isEqualTo(401);
        assertThat(ErrorCode.SCOPE_DENIED.code()).isEqualTo("FA-2006");
        assertThat(ErrorCode.SCOPE_DENIED.httpStatus().value()).isEqualTo(403);
        assertThat(ErrorCode.CLASS_HAS_STUDENTS.code()).isEqualTo("FA-2005");
        assertThat(ErrorCode.CLASS_HAS_STUDENTS.httpStatus().value()).isEqualTo(409);
        assertThat(ErrorCode.RETRY_EXCEEDED.httpStatus().value()).isEqualTo(429);
    }
}
