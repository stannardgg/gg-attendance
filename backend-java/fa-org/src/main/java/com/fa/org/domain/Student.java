package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 学生档案。class_id 是唯一归属——一人同时只在一个班（§5.3.1）。
 */
@Data
@TableName("student")
public class Student {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 激活后回填；未激活为 null */
    private Long accountId;

    private String studentNo;
    private String name;

    /** ★ 唯一归属班级；转班 = 改本字段 + 留审计 */
    private Long classId;

    /** ACTIVE / TRANSFERRED / ARCHIVED */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
