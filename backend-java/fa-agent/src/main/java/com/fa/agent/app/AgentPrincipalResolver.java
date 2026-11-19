package com.fa.agent.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fa.auth.security.TokenBlacklist;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.security.CurrentUser;
import com.fa.common.security.JwtUtil;
import com.fa.common.security.ScopeContext;
import com.fa.org.domain.TeacherScope;
import com.fa.org.infra.TeacherScopeMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把 {@code X-User-Token} 解析成 {@link AgentPrincipal}。
 *
 * <p>这里是整个 Agent 通道的安全入口，三件事一件都不能省：
 * <ol>
 *   <li>验签 + 过期（伪造/过期一律 FA-1002，不向下传递细节）；</li>
 *   <li>查黑名单（登出后的令牌必须立刻失效，否则「登出」只是前端删了 localStorage）；</li>
 *   <li>只接受 access 令牌——refresh token 只该用于换新，不该能调业务接口。</li>
 * </ol>
 *
 * <p>数据范围在这里一次性算好：管理员 = 全量；教师 = {@code teacher_scope} 的并集；
 * 其它角色给空集（后续判断会自然拒绝，而不是放行）。
 */
@Component
public class AgentPrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(AgentPrincipalResolver.class);

    private final JwtUtil jwtUtil;
    private final TokenBlacklist blacklist;
    private final TeacherScopeMapper scopeMapper;

    public AgentPrincipalResolver(JwtUtil jwtUtil, TokenBlacklist blacklist,
                                  TeacherScopeMapper scopeMapper) {
        this.jwtUtil = jwtUtil;
        this.blacklist = blacklist;
        this.scopeMapper = scopeMapper;
    }

    public AgentPrincipal resolve(String userToken) {
        if (userToken == null || userToken.isBlank()) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "缺少 X-User-Token");
        }
        Claims claims;
        try {
            claims = jwtUtil.parse(userToken.trim());
        } catch (JwtException | IllegalArgumentException e) {
            // 不把 e.getMessage() 回给调用方：里面有签名/时间等可用于试探的信息
            log.warn("agent-token-invalid type={}", e.getClass().getSimpleName());
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }
        var payload = jwtUtil.toPayload(claims);
        if (!JwtUtil.TYPE_ACCESS.equals(payload.tokenType())) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "该令牌不能用于业务接口");
        }
        if (blacklist.isRevoked(payload.jti())) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "该令牌已登出");
        }
        CurrentUser user = new CurrentUser(
                payload.accountId(), payload.username(), payload.role(),
                payload.teacherId(), payload.studentId(), payload.jti(), payload.tokenType());
        return new AgentPrincipal(user, loadScope(user));
    }

    private ScopeContext.Snapshot loadScope(CurrentUser user) {
        if (user.isAdmin()) {
            return ScopeContext.Snapshot.adminAll();
        }
        if (!user.isTeacher() || user.teacherId() == null) {
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
                log.error("illegal scope_type={} teacherId={} scopeId={}",
                        s.getScopeType(), s.getTeacherId(), s.getScopeId());
            }
        }
        return new ScopeContext.Snapshot(false, Set.copyOf(classIds), Set.copyOf(courseIds));
    }
}
