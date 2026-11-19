package com.fa.auth.security;

import com.fa.common.api.Result;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.JwtUtil;
import com.fa.common.web.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 鉴权过滤器。
 *
 * <p>两个刻意的行为：
 * ① <b>没带令牌就放行</b>（匿名），由 SecurityConfig 的授权规则和 EntryPoint 决定是否 401。
 * 这样 permitAll 的端点（登录、健康检查）不用特判。
 * ② <b>带了令牌但无效/过期，直接返回 401 FA-1002</b>，不继续往下走。
 * 否则会先被当成匿名，最后报 403 FA-1003——前端会把「登录过期」显示成「权限不足」，
 * 用户完全不知道该重新登录。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklist blacklist;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtUtil jwtUtil, TokenBlacklist blacklist, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.blacklist = blacklist;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(7).trim();
        Claims claims;
        try {
            claims = jwtUtil.parse(token);
        } catch (JwtException | IllegalArgumentException e) {
            writeUnauthorized(response);
            return;
        }

        var payload = jwtUtil.toPayload(claims);
        if (blacklist.isRevoked(payload.jti())) {
            writeUnauthorized(response);
            return;
        }

        CurrentUser user = new CurrentUser(
                payload.accountId(), payload.username(), payload.role(),
                payload.teacherId(), payload.studentId(),
                payload.jti(), payload.tokenType());

        var authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + payload.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        ErrorCode code = ErrorCode.TOKEN_INVALID;
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                Result.fail(code.code(), code.message()));
        response.getWriter().write("");
        response.flushBuffer();
        // traceId 不在响应体里：这里绕过了 ResultTraceAdvice（过滤器早于 MVC），
        // 但响应头已由 TraceIdFilter 写入，排查时够用。
        response.setHeader("X-Trace-Id", TraceContext.currentTraceId());
    }
}
