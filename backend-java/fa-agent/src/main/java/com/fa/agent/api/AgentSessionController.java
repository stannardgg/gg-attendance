package com.fa.agent.api;

import com.fa.agent.api.dto.AgentDtos;
import com.fa.agent.app.AgentSsoService;
import com.fa.common.api.Result;
import com.fa.common.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端换取 Agent 一次性 code（契约 java-api.yaml {@code POST /api/v1/agent/session}）。
 *
 * <p>这个端点走**普通用户鉴权**（Authorization: Bearer），不是内部令牌：
 * 它是浏览器点"智能助手"时调用的，代表的是当前登录的人。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentSessionController {

    private final AgentSsoService sso;

    public AgentSessionController(AgentSsoService sso) {
        this.sso = sso;
    }

    @PostMapping("/session")
    public Result<AgentSsoService.CodeData> session(HttpServletRequest request) {
        var user = SecurityUtils.current();
        String token = bearer(request);
        return Result.ok(sso.issue(token, user.accountId(), user.role()));
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7).trim();
    }
}
