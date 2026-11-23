package com.fa.org.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 课程-学生授权（CR-026）。
 *
 * <p>这是一张<b>授权表</b>（与 {@link TeacherScope} 同类），不是选课表：
 * 班级名单仍是默认应到，授权学生只是**追加**。它不使班级与课程产生依赖——
 * {@code course} 依然没有 classId（D7 主体不动）。
 *
 * <p>撤销授权 = 物理删除行（teacher_scope 同风格，审计里有痕迹）；
 * 没有 status 列：授权名单只在会话创建那一刻被读，软删没有业务语义。
 */
@Data
@TableName("course_student_grant")
public class CourseGrant {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long courseId;

    private Long studentId;

    /** 操作人（account.id），审计冗余 */
    private Long grantedBy;

    private LocalDateTime createdAt;
}
