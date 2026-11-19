package com.fa.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法需要按 teacher_scope 注入数据范围。
 *
 * <p>为什么用注解 + 切面而不是在每个查询里手写 where 条件：手写一定会漏。
 * 漏掉的那一处不会报错，只会把别的班的数据返回出去——这类 bug 只在越权测试里才暴露。
 *
 * <p>ADMIN 跳过过滤；TEACHER 必须命中 {@code teacher_scope}；STUDENT 不走这个注解
 * （学生只查本人数据，范围由 service 直接按 studentId 限定）。
 *
 * <p>切面实现位于 fa-org（它才有 teacher_scope 的 Mapper），见 {@code DataScopeAspect}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
}
