package com.fa.org.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.DataScope;
import com.fa.common.security.ScopeContext;
import com.fa.common.security.SecurityUtils;
import com.fa.org.domain.TeacherScope;
import com.fa.org.infra.TeacherScopeMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 数据范围切面。实现 {@link DataScope} 的实际语义（项目文档 §3.1）。
 *
 * <p>放在 fa-org 而不是 fa-common，是因为它要查 {@code teacher_scope} 表——
 * 让 common 依赖 Mapper 会把「通用层」变成「什么都依赖」。
 *
 * <p>关键取舍：<b>先建立上下文，再执行方法，finally 必清理</b>。
 * ThreadLocal 不清会污染线程池里的下一个请求，这是最典型的越权来源之一。
 */
@Aspect
@Component
public class DataScopeAspect {

    private static final Logger log = LoggerFactory.getLogger(DataScopeAspect.class);

    private final TeacherScopeMapper scopeMapper;

    public DataScopeAspect(TeacherScopeMapper scopeMapper) {
        this.scopeMapper = scopeMapper;
    }

    @Around("@annotation(com.fa.common.security.DataScope)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        CurrentUser user = SecurityUtils.currentOrNull();
        if (user == null) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        ScopeContext.Snapshot snapshot = resolve(user);
        ScopeContext.set(snapshot);
        try {
            return pjp.proceed();
        } finally {
            ScopeContext.clear();
        }
    }

    ScopeContext.Snapshot resolve(CurrentUser user) {
        if (user.isAdmin()) {
            return ScopeContext.Snapshot.adminAll();
        }
        if (!user.isTeacher() || user.teacherId() == null) {
            // 学生不该走到带数据范围的接口；给空集，让后续校验自然拒绝
            return new ScopeContext.Snapshot(false, Set.of(), Set.of());
        }

        List<TeacherScope> scopes = scopeMapper.selectList(
                Wrappers.<TeacherScope>lambdaQuery().eq(TeacherScope::getTeacherId, user.teacherId()));

        Set<Long> classIds = new HashSet<>();
        Set<Long> courseIds = new HashSet<>();
        for (TeacherScope s : scopes) {
            if ("CLASS".equals(s.getScopeType())) {
                classIds.add(s.getScopeId());
            } else if ("COURSE".equals(s.getScopeType())) {
                courseIds.add(s.getScopeId());
            } else {
                // 契约只允许 CLASS/COURSE。出现别的值说明库被手改过，必须暴露而不是忽略
                log.error("illegal scope_type={} teacherId={} scopeId={}",
                        s.getScopeType(), s.getTeacherId(), s.getScopeId());
            }
        }
        return new ScopeContext.Snapshot(false, Set.copyOf(classIds), Set.copyOf(courseIds));
    }
}
