package com.fa.auth.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 账号创建闸门（§9.3：任何 role=ADMIN 的创建请求直接 FA-1005）。
 *
 * <p>为什么值得单独测：这条规则保护的是「世界上只有一个管理员」。
 * 它失效不会报错，只会安静地多出一个能删库的账号。
 */
class AccountFactoryTest {

    @Test
    @DisplayName("角色为 ADMIN 时抛 FA-1005")
    void adminCreationDenied() {
        assertThatThrownBy(() -> AccountFactory.assertCreatableRole("ADMIN"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).errorCode())
                        .isEqualTo(ErrorCode.ADMIN_CREATION_DENIED));
    }

    @Test
    @DisplayName("大小写混写也拦（admin / Admin）")
    void caseInsensitive() {
        assertThatThrownBy(() -> AccountFactory.assertCreatableRole("admin"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> AccountFactory.assertCreatableRole("Admin"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("TEACHER / STUDENT 放行")
    void allowedRoles() {
        assertThatCode(() -> AccountFactory.assertCreatableRole("TEACHER")).doesNotThrowAnyException();
        assertThatCode(() -> AccountFactory.assertCreatableRole("STUDENT")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未知角色拒绝，避免拼错值绕过校验")
    void unknownRole() {
        assertThatThrownBy(() -> AccountFactory.assertCreatableRole("TEACHERR"))
                .isInstanceOf(BizException.class);
    }
}
