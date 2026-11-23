package com.fa.org.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 课程相关 DTO。
 *
 * <p>★ 这里没有、也不允许有 classId —— 课程不含任何班级信息（D7）。
 * 如果有人给 {@code CreateReq} 加一个 {@code Long classId}，契约断言脚本不一定能发现，
 * 但 OpenAPI 与前端 codegen 会立刻对不上；评审时请盯住这一点。
 */
public final class CourseDtos {

    private CourseDtos() {
    }

    public record CreateReq(
            @NotBlank
            @Pattern(regexp = "^[A-Z0-9]{2,32}$", message = "课程编码须为 2~32 位大写字母数字")
            String code,

            @NotBlank @Size(max = 120) String name,

            @Pattern(regexp = "^\\d{4}-\\d{4}-[12]$", message = "学期格式应形如 2026-2027-1")
            String semester,

            Long ownerTeacherId,
            @Size(max = 255) String remark) {
    }

    /** 不含 code，也不含任何班级字段。 */
    public record UpdateReq(
            @Size(max = 120) String name,
            @Pattern(regexp = "^\\d{4}-\\d{4}-[12]$", message = "学期格式应形如 2026-2027-1")
            String semester,
            Long ownerTeacherId,
            @Pattern(regexp = "^(ACTIVE|ARCHIVED)$", message = "状态只能是 ACTIVE 或 ARCHIVED")
            String status,
            @Size(max = 255) String remark) {
    }

    public record Resp(
            Long id,
            String code,
            String name,
            String semester,
            Long ownerTeacherId,
            String ownerTeacherName,
            String status,
            String remark,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    // ---- CR-026 课程授权学生（挂课程子资源 /courses/{courseId}/grants） ----

    public record GrantReq(@NotNull Long studentId) {
    }

    /**
     * 授权名单行。学生姓名/学号/班级为展示冗余——授权表的业务键只有
     * courseId + studentId，名字拼进来是为了让教师对得上人。
     */
    public record GrantResp(
            Long id,
            Long courseId,
            Long studentId,
            String studentName,
            String studentNo,
            Long classId,
            String className,
            Long grantedBy,
            LocalDateTime createdAt) {
    }
}
