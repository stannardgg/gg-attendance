package com.fa.common.security;

import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录用户。仅作为 SecurityContext 的便捷读取口，不做任何鉴权判断——
 * 真正的鉴权在过滤器与 {@code @PreAuthorize} 上，前端隐藏按钮不算数（§8.3）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static CurrentUser currentOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return principal instanceof CurrentUser u ? u : null;
    }

    public static CurrentUser current() {
        CurrentUser user = currentOrNull();
        if (user == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        return user;
    }

    public static long currentAccountId() {
        return current().accountId();
    }
}
