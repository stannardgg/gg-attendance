package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 班级主数据。
 *
 * <p>★ 注意这里<b>没有</b> courseId 之类的字段。班级与课程彻底解耦是 D7 的核心决策，
 * 契约断言脚本（contracts/scripts/check-contract.sh）会扫数据库确认没人偷加。
 */
@Data
@TableName("class_group")
public class ClassGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 班级邀请码，学生激活时使用；创建后不可修改 */
    private String code;

    private String name;
    private String grade;
    private String major;
    private Long ownerTeacherId;
    private String status;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
