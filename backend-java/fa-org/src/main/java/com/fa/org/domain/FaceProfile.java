package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 人脸档案状态机：NONE → PENDING_REVIEW → ACTIVE / REJECTED。
 * 只有 ACTIVE 允许签到（FA-4006）。
 */
@Data
@TableName("face_profile")
public class FaceProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;
    private String status;
    private Integer sampleCount;
    private LocalDateTime submittedAt;
    private Long reviewedBy;
    private LocalDateTime reviewedAt;
    private String rejectReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
