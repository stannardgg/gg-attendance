package com.fa.auth.api;

import com.fa.auth.api.dto.AuthDtos;
import com.fa.auth.app.AuthService;
import com.fa.common.api.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证端点。除 {@code /me} 与 {@code /logout} 外都不需要令牌。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Result<AuthDtos.TokenData> login(@Valid @RequestBody AuthDtos.LoginReq req) {
        return Result.ok(authService.login(req));
    }

    @PostMapping("/refresh")
    public Result<AuthDtos.TokenData> refresh(@Valid @RequestBody AuthDtos.RefreshReq req) {
        return Result.ok(authService.refresh(req.refreshToken()));
    }

    /**
     * 登出。令牌通过 Header 传入——从 SecurityContext 拿不到原始字符串，
     * 而黑名单需要 jti 与剩余有效期，所以这里显式读 header。
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : authorization;
        authService.logout(token);
        return Result.ok();
    }

    @PostMapping("/student/activate")
    public Result<AuthDtos.TokenData> activate(@Valid @RequestBody AuthDtos.ActivateReq req) {
        return Result.ok(authService.activate(req));
    }

    @GetMapping("/me")
    public Result<AuthDtos.MeData> me() {
        return Result.ok(authService.me());
    }
}
