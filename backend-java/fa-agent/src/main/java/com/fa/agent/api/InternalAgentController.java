package com.fa.agent.api;

import com.fa.agent.api.dto.AgentDtos;
import com.fa.agent.app.AgentPrincipal;
import com.fa.agent.app.AgentPrincipalResolver;
import com.fa.agent.app.AgentToolService;
import com.fa.common.api.Result;
import com.fa.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 内部端点（契约 {@code contracts/openapi/internal.yaml}）。
 *
 * <p><b>为什么这三个端点必须存在</b>：Agent 进程不持有数据库凭据，也不能自己签发确认令牌。
 * 工具目录、预览/执行、撤销三件事都落在 Java，Agent 只做编排——这是 §11.4 的硬边界。
 *
 * <p><b>鉴权是两层</b>：
 * <ol>
 *   <li>{@code X-Internal-Token}（由 {@code InternalTokenFilter} 在过滤器链上校验），
 *       证明调用方是我们自己的 Agent 进程；</li>
 *   <li>{@code X-User-Token}（终端用户的 access token），证明「以谁的身份」在操作。
 *       两者缺一不可——只有内网令牌等于 Agent 可以冒充任何人。</li>
 * </ol>
 *
 * <p>注意：{@code /internal/**} 在 SecurityConfig 里是 permitAll，
 * 所以这里手工建立 SecurityContext。不这么做的话，下游 Service 里的
 * {@code SecurityUtils.current()} 会抛 FA-1002，看起来像「令牌过期」，实际是没建立上下文。
 */
@RestController
@RequestMapping("/internal/agent")
public class InternalAgentController {

    private final AgentPrincipalResolver resolver;
    private final AgentToolService tools;

    public InternalAgentController(AgentPrincipalResolver resolver, AgentToolService tools) {
        this.resolver = resolver;
        this.tools = tools;
    }

    @GetMapping("/tools/catalog")
    public Result<AgentDtos.CatalogData> catalog(
            @RequestParam(required = false) Long userId,
            HttpServletRequest request) {
        return withIdentity(request, principal -> Result.ok(tools.catalog(principal)));
    }

    @PostMapping("/tools/{tool}")
    public Result<AgentDtos.ToolData> invoke(@PathVariable String tool,
                                             @RequestBody(required = false) AgentDtos.ToolReq body,
                                             HttpServletRequest request) {
        AgentDtos.ToolReq req = body == null
                ? new AgentDtos.ToolReq(Map.of(), null, null) : body;
        return withIdentity(request, principal -> Result.ok(tools.invoke(
                principal, tool, req.params() == null ? Map.of() : req.params(),
                req.confirmToken(), req.toolCallId(),
                header(request, "X-Conversation-Id"))));
    }

    /**
     * 撤销。契约里返回 {@code AgentToolResp}，这里沿用同一信封，
     * data 用 {@link AgentDtos.UndoData}（执行与撤销同构，Agent 侧少一套解析）。
     */
    @PostMapping("/actions/{id}/undo")
    public Result<AgentDtos.UndoData> undo(@PathVariable long id, HttpServletRequest request) {
        return withIdentity(request, principal -> Result.ok(tools.undo(principal, id)));
    }

    /**
     * 解析身份、建立 SecurityContext、执行、清理。
     *
     * <p>清理放在 finally 是必须的：线程池复用会把上一个人的身份带进下一个请求，
     * 这是最典型也最难复现的越权来源。
     */
    private <T> T withIdentity(HttpServletRequest request, Handler<T> handler) {
        AgentPrincipal principal = resolver.resolve(header(request, "X-User-Token"));
        CurrentUser user = principal.user();
        var authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            return handler.handle(principal);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @FunctionalInterface
    private interface Handler<T> {
        T handle(AgentPrincipal principal);
    }

    private static String header(HttpServletRequest request, String name) {
        return request.getHeader(name);
    }
}
