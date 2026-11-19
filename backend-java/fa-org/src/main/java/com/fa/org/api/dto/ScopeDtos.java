package com.fa.org.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 教师授权范围 DTO。
 *
 * <p>scopeType 只有 CLASS / COURSE 两种取值（契约 enums/statuses.json）。
 * 这不是「课程属于班级」的表达，而是「这个教师能看哪个维度」的授权。
 */
public final class ScopeDtos {

    private ScopeDtos() {
    }

    public record CreateReq(
            @NotNull Long teacherId,

            @NotNull
            @Pattern(regexp = "^(CLASS|COURSE)$", message = "scopeType 只能是 CLASS 或 COURSE")
            String scopeType,

            @NotNull Long scopeId) {
    }

    public record Resp(
            Long id,
            Long teacherId,
            String teacherName,
            String scopeType,
            Long scopeId,
            String scopeName,
            Long grantedBy,
            LocalDateTime createdAt) {
    }

    public record FaceReviewReq(
            @NotNull Boolean approved,
            @Size(max = 255) String rejectReason) {
    }

    /**
     * 提交采集（契约 FaceEnrollReq）。
     *
     * <p>只收样本引用键，不收图片也不收向量：图片在 face 服务逐张质检，
     * 向量由 face 服务暂存 Redis，Java 按 key 取回落库（CR-009）。
     */
    public record FaceEnrollReq(
            @NotNull @Size(min = 5, max = 10) List<@NotBlank String> sampleKeys,
            @NotBlank @Size(max = 16) String consentVersion) {
    }

    public record FaceStatusResp(
            Long studentId,
            String status,
            Integer sampleCount,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt,
            String rejectReason) {
    }
}
