package com.fa.auth.security;

import com.fa.auth.domain.Account;
import com.fa.auth.infra.AccountMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 按 account.id 解析「当前生效的角色与状态」。
 *
 * <p>为什么需要它：JWT 里的 {@code role} 是签发那一刻的快照，有效期 30 分钟。
 * 一旦库里改了角色或停用了账号，这张令牌在过期前仍然"自带"旧权限，
 * 于是出现两种都不该发生的情形：
 * <ul>
 *   <li>库里已是 ADMIN，但手里还是旧令牌 → 界面上是管理员（/auth/me 读库），
 *       接口却说「权限不足」（鉴权读令牌），用户完全无从判断；</li>
 *   <li>教师被停用后仍能用旧令牌操作最多 30 分钟。</li>
 * </ul>
 * 所以鉴权改成以库为准，令牌只负责证明"你是谁"。
 *
 * <p>成本：每个账号 30 秒最多一次主键查询；写路径（停用/删除教师）主动
 * {@link #invalidate(long)}，不需要等缓存过期。多实例部署下其余实例最多滞后 30 秒，
 * 这个窗口比原来的 30 分钟小两个数量级，且不会造成越权（只会多拒一小会儿）。
 */
@Component
public class AccountRoleResolver {

    private static final long TTL_NANOS = Duration.ofSeconds(30).toNanos();

    private final AccountMapper accountMapper;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public AccountRoleResolver(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    /** 账号不存在时返回 null —— 调用方据此判定令牌指向的主体已消失。 */
    public Snapshot resolve(long accountId) {
        long now = System.nanoTime();
        Entry cached = cache.get(accountId);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.snapshot();
        }
        Account account = accountMapper.selectById(accountId);
        if (account == null) {
            cache.remove(accountId);
            return null;
        }
        Snapshot snapshot = new Snapshot(account.getRole(), account.getStatus(),
                account.getCredentialVersion() == null ? 0 : account.getCredentialVersion());
        cache.put(accountId, new Entry(snapshot, now + TTL_NANOS));
        return snapshot;
    }

    public void invalidate(long accountId) {
        cache.remove(accountId);
    }

    /**
     * @param credentialVersion 凭据版本，改密/改登录名后 +1；令牌 cv 不符即失效
     */
    public record Snapshot(String role, String status, int credentialVersion) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    private record Entry(Snapshot snapshot, long expiresAtNanos) {
    }
}
