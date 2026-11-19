package com.fa.auth.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 教师档案。教师账号只能由 ADMIN 创建（§3.1）。
 */
@Data
@TableName("teacher")
public class Teacher {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long accountId;

    /** 工号，同时作为 account.username */
    private String teacherNo;

    private String name;
    private String department;
    private String title;

    /** ACTIVE / DISABLED */
    private String status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
