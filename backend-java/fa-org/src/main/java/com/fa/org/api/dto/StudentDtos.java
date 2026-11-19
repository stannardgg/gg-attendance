package com.fa.org.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学生相关 DTO，含 CSV 导入的逐行结果。
 */
public final class StudentDtos {

    private StudentDtos() {
    }

    public record CreateReq(
            @NotBlank @Size(max = 32) String studentNo,
            @NotBlank @Size(max = 64) String name,
            @NotNull Long classId) {
    }

    /** classId 改动即视为转班，服务端会写审计。 */
    public record UpdateReq(
            @Size(max = 64) String name,
            Long classId,
            @Pattern(regexp = "^(ACTIVE|TRANSFERRED|ARCHIVED)$", message = "状态不合法")
            String status) {
    }

    public record Resp(
            Long id,
            String studentNo,
            String name,
            Long classId,
            String className,
            String status,
            boolean activated,
            String faceStatus,
            LocalDateTime createdAt) {
    }

    /** CSV 的失败行。line 从 1 起（含表头），方便对着 Excel 行号去找。 */
    public record ImportRowError(int line, String studentNo, String reason) {
    }

    public record ImportResult(
            int total,
            int created,
            int updated,
            int failed,
            List<ImportRowError> errors) {
    }
}
