package com.fa.checkin.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 签到域 DTO。字段严格对齐 contracts/openapi/java-api.yaml 的
 * SessionCreateReq / Session / QrData / JoinData / SessionContext / CheckinCommitReq。
 */
public final class CheckinDtos {

    private CheckinDtos() {
    }

    /** ★ classId 与 courseId 都必填，缺任一个即 FA-2001（§6.4）。 */
    public record CreateReq(
            @NotNull Long classId,
            @NotNull Long courseId,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Min(1) @Max(240) Integer durationMinutes,
            @Min(0) @Max(7200) Integer lateAfterSec,
            Boolean allowAppeal) {
    }

    public record Session(
            Long id,
            String title,
            Long classId,
            String className,
            Long courseId,
            String courseName,
            Long teacherId,
            String teacherName,
            String status,
            LocalDateTime startAt,
            LocalDateTime endAt,
            Integer lateAfterSec,
            Boolean allowAppeal,
            long expectedCount,
            long presentCount,
            long lateCount,
            long absentCount,
            LocalDateTime closedAt,
            LocalDateTime createdAt) {
    }

    public record QrData(
            Long sessionId,
            String token,
            String qrUrl,
            LocalDateTime expiresAt,
            Integer qrEpoch,
            String className,
            String courseName) {
    }

    /** 内部 context：Python 用它快速拒绝不该签到的人。 */
    public record ContextResp(
            Long sessionId,
            String status,
            Long classId,
            Long courseId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            boolean studentInClass,
            String studentStatus,
            boolean faceActive,
            boolean allowAppeal) {
    }

    public record CommitReq(
            @NotNull Long sessionId,
            @NotNull Long studentId,
            @NotNull BigDecimal similarity,
            BigDecimal livenessMedian,
            BigDecimal identitySim,
            Integer actionCount,
            String actionSeq,
            String deviceHash,
            String clientIp,
            String snapshotRef) {
    }

    public record CommitData(
            Long attendanceId,
            String status,
            LocalDateTime checkinAt,
            boolean duplicate,
            // 大屏推送要显示姓名：只给 studentId 的话前端只能推"有学生"，或者再请求一次
            String studentName) {
    }

    /**
     * join 请求体（契约 /mobile/sessions/{sid}/join 的 requestBody）。
     *
     * <p>qrToken 是「人到现场」的唯一凭证。少了它，任何知道 sessionId 的人
     * 都能远程换票签到，二维码就退化成装饰品。
     */
    public record JoinReq(
            @NotBlank @Size(min = 16, max = 16) String qrToken) {
    }

    public record JoinData(
            String checkinTicket,
            int ttl,
            String className,
            String courseName,
            String sessionTitle,
            long remainingSec,
            boolean challengeRequired,
            int retryLeft) {
    }

    public record ActiveSession(
            Long sessionId,
            String title,
            String className,
            String courseName,
            LocalDateTime startAt,
            LocalDateTime endAt,
            long remainingSec,
            String myStatus) {
    }

    public record MobileAttendance(
            Long id,
            Long sessionId,
            String sessionTitle,
            String className,
            String courseName,
            String status,
            LocalDateTime checkinAt,
            boolean canAppeal) {
    }

    public record HistoryPage(List<MobileAttendance> items, long total, int page, int size) {
    }
}
