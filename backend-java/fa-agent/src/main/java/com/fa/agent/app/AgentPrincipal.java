package com.fa.agent.app;

import com.fa.common.security.CurrentUser;
import com.fa.common.security.ScopeContext;
import java.util.Set;

/**
 * 一次 Agent 工具调用里的「身份 + 数据范围」。
 *
 * <p><b>为什么不直接用 {@link ScopeContext} 的 ThreadLocal</b>：
 * {@code @DataScope} 切面在方法返回的 finally 里会 clear 上下文，
 * 而一次工具调用里可能连着调多个 Service（比如先查会话、再查学生），
 * 中间那次 clear 会让后续判断拿到 IllegalStateException。
 * 所以这里持有一份明确的快照，显式传给每个判断点，不依赖线程状态。
 *
 * <p>身份来源是 {@code X-User-Token} 请求头（终端用户的 access token），
 * <b>不信任请求体里的任何 userId</b>（契约 internal.yaml 明确要求）。
 */
public record AgentPrincipal(CurrentUser user, ScopeContext.Snapshot scope) {

    public long accountId() {
        return user.accountId();
    }

    public Long teacherId() {
        return user.teacherId();
    }

    public boolean isAdmin() {
        return user.isAdmin();
    }

    /** 只有教师与管理员能使用 Agent 工具；学生不在工具能力面内（§11.1）。 */
    public boolean canUseAgent() {
        return user.isAdmin() || user.isTeacher();
    }

    public boolean allowsClass(Long classId) {
        return scope.allowsClass(classId);
    }

    public boolean allowsCourse(Long courseId) {
        return scope.allowsCourse(courseId);
    }

    public Set<Long> classIds() {
        return scope.classIds();
    }

    public Set<Long> courseIds() {
        return scope.courseIds();
    }
}
