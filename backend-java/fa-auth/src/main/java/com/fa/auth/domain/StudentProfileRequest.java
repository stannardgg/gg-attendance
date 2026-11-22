package com.fa.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 学生密码修改申请（CR-024）。
 *
 * <p>学生忘了密码只能线下找管理员——本表把这条链变成：学生提交申请 →
 * 管理员审核 → 通过才写 account.password_hash 并 cv+1 使旧令牌失效。
 * 独立于 teacher_profile_request：那张表 teacher_id NOT NULL 且
 * uk_tpr_pending 生成列绑定 teacher_id，塞 student_id 语义就乱了；
 * 学生侧当前只放开「改密」一种申请，将来若放开资料字段照 V6 方式加列。
 *
 * <p>★ pending_student 是数据库生成列，实体里不能有它——写了 insert 会报
 * generated column cannot be assigned（与 Appeal、TeacherProfileRequest 同理）。
 */
@Data
@TableName("student_profile_request")
public class StudentProfileRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;
    /** 申请人 account.id */
    private Long accountId;

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
