package com.fa.auth.app;

import com.fa.auth.domain.Account;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 账号创建的<b>唯一入口</b>。
 *
 * <p>为什么单独抽出来：§9.3 要求「任何 role=ADMIN 的创建请求直接 FA-1005」。
 * 如果每个业务方自己 new Account，这条规则就得复制 N 遍，漏一处就是提权漏洞。
 * 收口到一个工厂后，规则只在这里出现一次，且有单测盯着。
 *
 * <p>数据库层还有第二道：{@code account.uk_single_admin}（生成列唯一索引）。
 * 即便有人绕过这里直接写库，也只能存在一个 ADMIN。
 */
@Component
public class AccountFactory {

    private final PasswordEncoder passwordEncoder;

    public AccountFactory(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @param role TEACHER / STUDENT
     * @throws BizException FA-1005 当 role 为 ADMIN
     */
    public Account create(String username, String rawPassword, String role) {
        assertCreatableRole(role);

        Account account = new Account();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(rawPassword));
        account.setRole(role);
        account.setStatus("ACTIVE");
        return account;
    }

    /** 独立成静态方法，便于单测直接验证规则本身。 */
    public static void assertCreatableRole(String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            throw new BizException(ErrorCode.ADMIN_CREATION_DENIED);
        }
        if (!"TEACHER".equals(role) && !"STUDENT".equals(role)) {
            throw new BizException(ErrorCode.FORBIDDEN, "非法角色：" + role);
        }
    }
}
