package com.fa.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 教师资料修改申请（CR-019）。
 *
 * <p>只承载「教师想改成什么」，不直接改 teacher 表：通过审核后才由
 * {@code TeacherProfileRequestService.handle} 落库，改前改后进 audit_log。
 *
 * <p>old_* 是提交时刻的快照，用于审核页展示 diff；审批时以 new_* 为准。
 *
 * <p>★ pending_teacher 是数据库生成列，实体里不能有它——写了 insert 会报
 * generated column cannot be assigned（与 Appeal.pending_session_student 同理）。
 */
@Data
@TableName("teacher_profile_request")
public class TeacherProfileRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teacherId;
    /** 申请人 account.id */
    private Long accountId;

    private String oldUsername;
    private String oldName;
    private String oldDepartment;
    private String oldTitle;

    /** NULL 表示本次未申请该字段 */
    private String newUsername;
    private String newName;
    private String newDepartment;
    private String newTitle;

    /** 1 = 本次申请重置密码；稳定标记，审核后仍保留供历史展示 */
    private Boolean passwordChange;

    /**
     * 新密码的 BCrypt 哈希，<b>仅暂存</b>。
     *
     * <p>提交时写入（明文不进库），审核通过后写入 account 并立刻清空；
     * 驳回/撤回同样清空。任何响应体与审计快照都不得携带它。
     */
    private String newPasswordHash;

    private String reason;

    /** PENDING / APPROVED / REJECTED / WITHDRAWN */
    private String status;

    private Long reviewerId;
    private String reviewNote;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
