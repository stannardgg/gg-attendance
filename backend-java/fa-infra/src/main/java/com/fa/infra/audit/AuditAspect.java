package com.fa.infra.audit;

import com.fa.common.audit.Audited;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * {@link Audited} 的切面实现。
 *
 * <p>记录内容刻意保守：动作、目标类型、方法名、入参摘要（截断 200 字）。
 * 不记返回值——返回值里常有学生姓名/相似度这类敏感字段，
 * 审计表是长期留存的数据，不该成为第二份业务数据的副本。
 */
@Aspect
@Component
public class AuditAspect {

    private final AuditWriter writer;

    public AuditAspect(AuditWriter writer) {
        this.writer = writer;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = pjp.proceed();          // 先执行；抛异常则不写审计
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            StringBuilder detail = new StringBuilder(sig.getMethod().getName()).append('(');
            Object[] args = pjp.getArgs();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    detail.append(", ");
                }
                String v = String.valueOf(args[i]);
                detail.append(v.length() > 60 ? v.substring(0, 60) + "…" : v);
            }
            detail.append(')');
            writer.write(audited.action(), audited.target(), null,
                    detail.length() > 200 ? detail.substring(0, 200) : detail.toString());
        } catch (RuntimeException ignored) {
            // 审计失败不能影响主流程：签到/审批比日志重要
        }
        return result;
    }
}
