package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 教师授权范围。
 *
 * <p>这是一张<b>授权表</b>，不是业务关联表。它让「教师能看什么」有了承载，
 * 同时不让班级与课程产生依赖——scopeType 只是标明 scopeId 指向哪张表（D7）。
 */
@Data
@TableName("teacher_scope")
public class TeacherScope {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long teacherId;

    /** CLASS / COURSE */
    private String scopeType;

    private Long scopeId;

    private Long grantedBy;
    private LocalDateTime createdAt;
}
