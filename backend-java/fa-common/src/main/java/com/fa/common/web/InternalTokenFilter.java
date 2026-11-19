package com.fa.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部接口令牌校验（§6.4：X-Internal-Token，常量时间比较）。
 *
 * <p>为什么在应用层再校验一次而不是只靠「容器网络隔离」：
 * 隔离是部署约定（§6.4 明确 /internal/** 不对公网暴露），但约定会被人忘记——
 * 本地起服务、端口误映射、内网其他服务都能摸到这些端点。
 * 一行常量时间比较的成本，换一个不会静默失效的防线，值得。
 *
 * <p>常量时间比较（MessageDigest.isEqual）防时序攻击：
 * 用 == 逐字符比的话，攻击者能靠响应时间差一位一位猜出令牌。
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public InternalTokenFilter(String expectedToken) {
        this.expected = expectedToken == null ? new byte[0]
                : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/internal/")) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader("X-Internal-Token");
        byte[] given = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (expected.length == 0 || !MessageDigest.isEqual(expected, given)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"code\":\"FA-1003\",\"message\":\"内部令牌无效\",\"data\":null,\"traceId\":\"\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
