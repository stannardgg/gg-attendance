package com.fa.checkin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 签到会话 = 班级 × 课程。
 *
 * <p>两个字段都必填，但职责完全不同：classId 决定应到名单，courseId 只用于统计口径（D7）。
 * 不要在这里加任何「课程属于班级」的推导——v2.0 不存在那种关系。
 */
@Data
@TableName("checkin_session")
public class CheckinSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long classId;
    private Long courseId;
    private Long teacherId;
    private String title;

    /** ACTIVE / CLOSED / CANCELLED */
    private String status;

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    /** 超过 start_at + lateAfterSec 签到即判 LATE */
    private Integer lateAfterSec;

    /** 递增即可作废旧二维码（教师「换码」） */
    private Integer qrEpoch;

    private Boolean allowAppeal;
    private LocalDateTime closedAt;
    private LocalDateTime createdAt;
}
