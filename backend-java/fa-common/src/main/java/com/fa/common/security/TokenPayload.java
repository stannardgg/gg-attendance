package com.fa.common.security;

/**
 * JWT 载荷的业务视图。与 {@link io.jsonwebtoken.Claims} 解耦，
 * 让下游只依赖这几个字段，换签名算法或换库时不用改业务代码。
 */
public record TokenPayload(
        long accountId,
        String username,
        String role,
        Long teacherId,
        Long studentId,
        String jti,
        String tokenType) {
}
