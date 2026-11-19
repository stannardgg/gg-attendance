package com.fa.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要写审计的方法。
 *
 * <p>为什么用注解而不是每个方法里手写 audit.write：手写容易漏——
 * 新加一个改数据的接口忘了写，不报错，只在排查事故时才发现没有记录。
 * 注解 + 切面把「写审计」变成默认行为，漏写的人必须主动不写。
 *
 * <p>只记成功调用：失败由异常处理器记，混在一起审计表会读不懂。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** 动作名，如 SESSION_CLOSE / APPEAL_HANDLE。 */
    String action();

    /** 目标类型，如 SESSION / APPEAL。 */
    String target();
}
