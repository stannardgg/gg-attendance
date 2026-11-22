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
 * SessionCreateReq / Session / JoinData / SessionContext / CheckinCommitReq。
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
     * join 响应（契约 /mobile/sessions/{sid}/join）。
     *
     * <p>{@code challengeRequired} 恒为 true：取消二维码后刷脸是唯一通道，
     * 字段保留是为了不动前端与契约结构。人脸档案不是 ACTIVE 时 join 直接
     * 返回 FA-4006，不会有「简化签到」的响应。
     */
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

    /** Python finish 判失败后上报（契约 /internal/checkin/attempt/fail）。rejectGate 仅作排障记录。 */
    public record AttemptFailReq(
            @NotNull Long sessionId,
            @NotNull Long studentId,
            String rejectGate) {
    }

    /**
     * 刷脸失败计数状态（CR-023，契约 /internal/checkin/attempt*）。
     *
     * <p>{@code blocked} 为 true 时：join 以 FA-3005 拒绝、首页显示「识别失败」
     * 并拒绝点击、Python start 拒绝再开挑战。计数只增不减——撤销/驳回申诉
     * 不清零，理由见 CR-023：申诉是老师的核对通道，不是重试次数的重置键。
     */
    public record AttemptData(
            Long sessionId,
            int failCount,
            int retryLeft,
            boolean blocked) {
    }

    public record ActiveSession(
            Long sessionId,
            String title,
            String className,
            String courseName,
            LocalDateTime startAt,
            LocalDateTime endAt,
            long remainingSec,
            String myStatus,
            /**
             * 本人对该场次最新一条申诉的状态；从未申诉为 null。
             *
             * <p>目前前端只用 PENDING —— 有在办申诉时首页显示「申诉中」并拒绝进入采集页，
             * 与后端 join 的 FA-5001 形成前后两道。其余状态（REJECTED/WITHDRAWN）照常可签。
             */
            String appealStatus,
            /**
             * 剩余可重试次数（CR-023）。0 = 识别失败已达上限：
             * 首页显示「识别失败」并拒绝点击，join 会以 FA-3005 拒绝。
             * 已签成功（PRESENT/LATE）时无意义，恒回填上限值，前端不读。
             */
            int retryLeft) {
    }

    /**
     * 学生端单条考勤视图。
     *
     * <p>{@code status} 允许为 null：会话进行中且本人尚未签到成功时**没有** attendance 行，
     * 但页面仍要显示班级/课程并允许发起申诉（AppealService 支持 attendanceId=0）。
     * 以前这里直接抛 FA-2001，导致学生刷脸失败后点「提交申诉」只看到一排「—」。
     */
    public record MobileAttendance(
            Long id,
            Long sessionId,
            String sessionTitle,
            String className,
            String courseName,
            String status,
            LocalDateTime checkinAt,
            boolean canAppeal,
            Long appealId,
            String appealStatus) {
    }

    public record HistoryPage(List<MobileAttendance> items, long total, int page, int size) {
    }
}
