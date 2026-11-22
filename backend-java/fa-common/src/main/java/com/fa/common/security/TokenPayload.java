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
        /** 签发时的凭据版本；与库中 account.credential_version 不符即视为失效 */
        int credentialVersion,
        String jti,
        String tokenType) {
}
