package com.fa.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 教师相关 DTO。
 *
 * <p>★ CreateReq 里<b>没有 role 字段</b>。新建教师的角色恒为 TEACHER，
 * 这是 ADMIN 独占能力的实现细节：不给调用方选择角色的机会，
 * 比「收到 role 就校验是不是 ADMIN」更安全（后者一旦校验写漏就是提权漏洞）。
 */
public final class TeacherDtos {

    private TeacherDtos() {
    }

    public record CreateReq(
            @NotBlank @Size(max = 32) String teacherNo,
            @NotBlank @Size(max = 64) String name,
            @NotBlank @Size(min = 8, max = 64)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码须同时包含字母与数字")
            String password,
            @Size(max = 120) String department,
            @Size(max = 64) String title) {
    }

    /**
     * 部分更新：字段为 null 表示"不改"。
     *
     * <p>院系/职称传空串表示"清空为 NULL"——JSON 无法区分"没传"与"传了 null"，
     * 所以清空只能用空串表达；姓名不允许清空（{@code @Pattern} 拦空白）。
     */
    public record UpdateReq(
            @Size(max = 64)
            @Pattern(regexp = ".*\\S.*", message = "姓名不能为空白")
            String name,
            @Size(max = 120) String department,
            @Size(max = 64) String title,
            @Pattern(regexp = "^(ACTIVE|DISABLED)$", message = "状态只能是 ACTIVE 或 DISABLED")
            String status,
            /**
             * 管理员直接重置密码（教师忘记密码时的兜底；教师自助改密走申请表）。
             * null = 不改；非空须满足与创建时相同的强度规则。空串会被 @Size 拦下。
             */
            @Size(min = 8, max = 64)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码须同时包含字母与数字")
            String password) {
    }

    public record Resp(
            Long id,
            String teacherNo,
            String name,
            String department,
            String title,
            String status,
            long classCount,
            long courseCount) {
    }
}
