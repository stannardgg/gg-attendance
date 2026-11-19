package com.fa.checkin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 申诉。uk_appeal_pending 用生成列保证「同一考勤只有一条 PENDING」。
 *
 * <p>★ pending_attendance_id 是数据库生成列，实体里不能有它——
 * 写了 insert 会报 generated column cannot be assigned。
 */
@Data
@TableName("appeal")
public class Appeal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long attendanceId;
    /** 冗余存储，避免列表回表 attendance */
    private Long sessionId;
    private Long studentId;
    private String reason;
    private String evidenceKey;

    /** PENDING / APPROVED / REJECTED / WITHDRAWN */
    private String status;

    private String reply;
    private Long handledBy;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
