package com.fa.agent.api;

import com.fa.agent.app.AgentSsoService;
import com.fa.common.api.Result;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 服务用一次性 code 换用户令牌（内部通道，{@code X-Internal-Token} 保护）。
 *
 * <p><b>为什么这个端点在契约里没有</b>：java-api.yaml 只定义了"签发 code"这一半，
 * 没有定义"消费 code"的另一半，Agent 进程无法拿 code 换到令牌。
 * 已登记为 CR-006，建议 v2.3 补进 internal.yaml；此处路径与请求体按 CR 的最小提案实现，
 * 不修改任何已冻结的契约文件。
 *
 * <p>请求体只有 {@code code} 一个字段。响应里的 token 就是用户在浏览器里持有的 access token，
 * <b>绝不写日志</b>——这个端点里没有任何 log 语句是刻意的。
 */
@RestController
@RequestMapping("/internal/agent/sso")
public class InternalAgentSsoController {

    private final AgentSsoService sso;

    public InternalAgentSsoController(AgentSsoService sso) {
        this.sso = sso;
    }

    public record ExchangeReq(String code) {
    }

    public record ExchangeResp(String token, Map<String, Object> profile) {
    }

    @PostMapping("/exchange")
    public Result<ExchangeResp> exchange(@RequestBody ExchangeReq req) {
        var result = sso.exchange(req.code());
        return Result.ok(new ExchangeResp(result.token(), result.profile()));
    }
}
