package com.fa.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 认证相关 DTO，对齐 contracts/openapi/java-api.yaml 的
 * LoginReq / TokenData / Profile / MeData / StudentActivateReq。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginReq(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 6, max = 64) String password) {
    }

    public record RefreshReq(@NotBlank String refreshToken) {
    }

    /**
     * 学生激活。三要素必须同时命中，任一不符返回同一句模糊提示（防探测，§5.3.1）。
     */
    public record ActivateReq(
            @NotBlank @Size(max = 32) String studentNo,
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(max = 32) String classCode,
            @NotBlank @Size(min = 8, max = 64)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码须同时包含字母与数字")
            String password) {
    }

    public record Profile(
            Long id,
            String username,
            String name,
            String role,
            Long teacherId,
            Long studentId,
            Long classId,
            String className,
            String faceStatus) {
    }

    public record ScopeInfo(List<Long> classIds, List<Long> courseIds) {
    }

    public record MeData(
            Profile profile,
            List<String> permissions,
            ScopeInfo scope,
            boolean faceConsentGiven) {
    }

    public record TokenData(
            String accessToken,
            String refreshToken,
            String role,
            long expiresIn,
            Profile profile) {
    }
}
