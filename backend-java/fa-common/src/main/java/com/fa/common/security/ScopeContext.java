package com.fa.common.security;

import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.util.Set;

/**
 * 当前请求的数据范围快照。由 {@code DataScopeAspect} 在进入 @DataScope 方法前写入，
 * 方法结束后清理。
 *
 * <p>核心原则：<b>没有范围信息时一律拒绝，而不是放行</b>。
 * 如果某个方法忘了加 {@code @DataScope}，读到这里会抛 IllegalStateException，
 * 在开发期就暴露；而如果默认放行，就会变成静默的越权漏洞。
 */
public final class ScopeContext {

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private ScopeContext() {
    }

    /**
     * @param admin     是否管理员（跳过滤）
     * @param classIds  被授权的班级 id
     * @param courseIds 被授权的课程 id
     */
    public record Snapshot(boolean admin, Set<Long> classIds, Set<Long> courseIds) {

        public static Snapshot adminAll() {
            return new Snapshot(true, Set.of(), Set.of());
        }

        public boolean allowsClass(Long classId) {
            return admin || (classId != null && classIds.contains(classId));
        }

        public boolean allowsCourse(Long courseId) {
            return admin || (courseId != null && courseIds.contains(courseId));
        }

        /** 列表查询用：返回可空集合，admin 返回 null 表示不加限制。 */
        public Set<Long> classFilter() {
            return admin ? null : classIds;
        }

        public Set<Long> courseFilter() {
            return admin ? null : courseIds;
        }
    }

    public static void set(Snapshot snapshot) {
        HOLDER.set(snapshot);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Snapshot current() {
        Snapshot s = HOLDER.get();
        if (s == null) {
            throw new IllegalStateException(
                    "未建立数据范围上下文：该方法需要标注 @DataScope，或从 SecurityContext 显式构造");
        }
        return s;
    }

    /** 校验对某个班级的访问权，不通过抛 FA-2006。 */
    public static void requireClass(Long classId) {
        if (!current().allowsClass(classId)) {
            throw new BizException(ErrorCode.SCOPE_DENIED);
        }
    }

    /** 校验对某门课程的访问权，不通过抛 FA-2006。 */
    public static void requireCourse(Long courseId) {
        if (!current().allowsCourse(courseId)) {
            throw new BizException(ErrorCode.SCOPE_DENIED);
        }
    }

    /**
     * 同时校验班级与课程。发起签到时必须两条都命中（项目文档 §3.1），
     * 缺一即 FA-2006。
     */
    public static void requireClassAndCourse(Long classId, Long courseId) {
        Snapshot s = current();
        if (!s.allowsClass(classId) || !s.allowsCourse(courseId)) {
            throw new BizException(ErrorCode.SCOPE_DENIED);
        }
    }
}
