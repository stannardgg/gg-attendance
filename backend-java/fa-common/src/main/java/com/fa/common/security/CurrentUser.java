package com.fa.common.security;

/**
 * 从 JWT 解析出的用户身份。作为 Spring Security 的 principal 放进 SecurityContext。
 *
 * <p>jti 保留下来是因为登出要把 jti 写进 Redis 黑名单——没有它就没法让单个令牌失效。
 */
public record CurrentUser(
        long accountId,
        String username,
        String role,
        Long teacherId,
        Long studentId,
        String jti,
        String tokenType) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isTeacher() {
        return "TEACHER".equals(role);
    }

    public boolean isStudent() {
        return "STUDENT".equals(role);
    }
}
