package com.fa.checkin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 考勤记录。uk_att_session_student 同时充当 commit 的幂等键。
 *
 * <p>注意 status 只有 PRESENT / LATE / ABSENT / LEAVE / EXEMPT——<b>没有 REVIEW</b>。
 * 识别落在 0.38~0.45 的「不够确定」区间时不落这条记录，直接回 FA-4005 或转申诉，
 * 这是契约里的明确选择：宁可让学生走申诉，也不让一条模棱两可的记录污染考勤表。
 */
@Data
@TableName("attendance")
public class Attendance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private Long studentId;
    private String status;
    private LocalDateTime checkinAt;

    /** 1:N top1 余弦相似度 */
    private BigDecimal similarity;

    /** 门禁1：多帧活体中位数 */
    private BigDecimal livenessMedian;

    /** 门禁2：首末帧余弦 */
    private BigDecimal identitySim;

    /** 门禁3：检测到的动作次数 */
    private Integer actionCount;

    private String rejectGate;
    private Long matchedVectorId;
    private String actionSeq;
    private String deviceHash;
    private String clientIp;
    private String snapshotRef;
    private String adjustReason;
    private Long adjustedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
