package com.fa.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.auth.domain.Account;
import com.fa.auth.infra.AccountMapper;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动时创建唯一的 ADMIN（§9.3）。
 *
 * <p>★ 这里<b>刻意绕过</b> {@code AccountFactory}：工厂会拒绝一切 ADMIN 创建请求（FA-1005），
 * 而系统必须有一种合法途径产生第一个管理员。
 * 这条途径只有一个出口——环境变量 {@code BOOTSTRAP_ADMIN_*}，且只在库中无 ADMIN 时生效。
 * 换句话说：ADMIN 只能由运维用环境变量创建，永远不能由 API 创建。
 *
 * <p>如果库中已有 ADMIN，本类不做任何事（包括不修改密码）——
 * 否则每次重启都会把管理员密码重置回环境变量的值，那是个后门。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin-username:admin}")
    private String adminUsername;

    @Value("${app.bootstrap.admin-password:}")
    private String adminPassword;

    public AdminBootstrap(AccountMapper accountMapper, PasswordEncoder passwordEncoder) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long existing = accountMapper.selectCount(
                Wrappers.<Account>lambdaQuery().eq(Account::getRole, "ADMIN"));
        if (existing != null && existing > 0) {
            return;
        }
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("库中无 ADMIN，且未配置 BOOTSTRAP_ADMIN_PASSWORD —— 跳过自动创建。"
                    + "请设置环境变量后重启，否则系统无人可登录管理端。");
            return;
        }

        Account admin = new Account();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(passwordEncoder.encode(adminPassword));
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        admin.setLastLoginAt(LocalDateTime.now());
        accountMapper.insert(admin);

        log.warn("已创建初始管理员 username={} —— 请立即登录并修改密码。", adminUsername);
    }
}
