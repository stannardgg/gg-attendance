package com.fa.org.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * 班级相关 DTO。
 *
 * <p>分组放在一个文件里：这些 record 只服务于一组端点，拆成 8 个文件会让
 * 「表单有什么字段」这件事散落在 8 处。契约是 contracts/openapi/java-api.yaml
 * 的 Class / ClassCreateReq / ClassUpdateReq，字段必须与之一致。
 *
 * <p>★ 全部 record 都<b>没有</b>课程字段。请求体走 {@code additionalProperties:false} 的等价物——
 * Spring 的 fail-on-unknown-properties，提交 classId 之类会被 400 拒绝。
 */
public final class ClassDtos {

    private ClassDtos() {
    }

    public record CreateReq(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9]{4,32}$",
                    message = "班级码须为 4~32 位字母数字")
            String code,

            @NotBlank @Size(max = 120) String name,
            @Size(max = 16) String grade,
            @Size(max = 120) String major,
            Long ownerTeacherId,
            @Size(max = 255) String remark) {
    }

    /** 不含 code —— 班级码被学生用于激活，创建后不可修改。 */
    public record UpdateReq(
            @Size(max = 120) String name,
            @Size(max = 16) String grade,
            @Size(max = 120) String major,
            Long ownerTeacherId,
            @Pattern(regexp = "^(ACTIVE|ARCHIVED)$", message = "状态只能是 ACTIVE 或 ARCHIVED")
            String status,
            @Size(max = 255) String remark) {
    }

    public record Resp(
            Long id,
            String code,
            String name,
            String grade,
            String major,
            Long ownerTeacherId,
            String ownerTeacherName,
            String status,
            String remark,
            long studentCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 删除/归档的结果。
     *
     * @param archived true = 因被引用只做了软停用；false = 物理删除
     */
    public record DeleteResult(@NotNull Long id, boolean archived) {
    }
}
