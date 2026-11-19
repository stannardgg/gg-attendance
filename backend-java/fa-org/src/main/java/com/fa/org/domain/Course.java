package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 课程主数据。
 *
 * <p>★ 这里<b>绝不</b>能有 classId / grade 之类字段。课程不含任何班级信息（D7），
 * 唯一键是 code；name 允许重名（不同学期可同名）。
 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 课程编码，如 MATH101；创建后不可修改 */
    private String code;

    private String name;

    /** 如 2026-2027-1，仅用于筛选与展示 */
    private String semester;

    private Long ownerTeacherId;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
