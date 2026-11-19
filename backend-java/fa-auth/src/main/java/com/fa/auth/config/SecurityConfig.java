package com.fa.auth.config;

import com.fa.auth.security.JwtAuthFilter;
import com.fa.common.api.Result;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.JwtUtil;
import com.fa.common.web.InternalTokenFilter;
import com.fa.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置。
 *
 * <p>三条设计决定：
 * ① <b>无状态</b>（STATELESS）：签到系统可能多实例，会话不能落在单机内存里。
 * ② <b>按 code 返回错误</b>：EntryPoint 与 AccessDeniedHandler 都输出统一响应包，
 * 而不是 Spring 默认的空 body + 状态码。前端只认 code（§6.2）。
 * ③ <b>不做 CSRF</b>：纯 Bearer 令牌 + 无 Cookie，CSRF 面不存在。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.access-ttl-seconds:1800}")
    private long accessTtlSeconds;

    @Value("${app.jwt.refresh-ttl-seconds:604800}")
    private long refreshTtlSeconds;

    @Bean
    public JwtUtil jwtUtil() {
        return new JwtUtil(jwtSecret,
                Duration.ofSeconds(accessTtlSeconds),
                Duration.ofSeconds(refreshTtlSeconds));
    }

    /** strength=10，与 §9.3 一致。改这个值会让历史哈希仍可验证（BCrypt 自带 cost），但新哈希变慢。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    /**
     * /internal/** 的令牌校验。放在 JWT 过滤器之前：
     * 内部调用方没有登录态，先于 Spring Security 放行/拦截，语义更直接。
     */
    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(
            @Value("${app.internal.token:dev-internal-token}") String token) {
        FilterRegistrationBean<InternalTokenFilter> bean =
                new FilterRegistrationBean<>(new InternalTokenFilter(token));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/internal/*");
        return bean;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter,
                                           ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/student/activate",
                                "/actuator/health",
                                "/error",
                                "/v3/api-docs/**").permitAll()
                        // /internal/** 不对公网暴露（§6.4），鉴权交给 InternalTokenFilter
                        // 而不是 JWT——它的消费方是 Python/Agent 服务，没有用户登录态。
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, res, ex) ->
                                write(res, objectMapper, ErrorCode.TOKEN_INVALID))
                        .accessDeniedHandler((req, res, ex) ->
                                write(res, objectMapper, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void write(HttpServletResponse response, ObjectMapper mapper, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), Result.fail(code.code(), code.message()));
    }
}
