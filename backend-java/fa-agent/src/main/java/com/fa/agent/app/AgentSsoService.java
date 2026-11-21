package com.fa.agent.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Agent 单点登录：一次性 code 换会话（D6）。
 *
 * <p>为什么不把 JWT 直接放进 iframe 的 URL：nginx 的 access_log 会原样记录 query string，
 * 令牌一旦进日志就等于泄漏，而且日志的保留期通常比令牌有效期长得多。
 * 换成 60 秒、单次消费的 code，即使被记进日志，等有人翻日志时它早就失效了。
 *
 * <p>code 里存的是调用方的 access token 原文（服务端 Redis，不落库、不进日志），
 * 因为 Agent 后续每个工具调用都要带 {@code X-User-Token} 代表这个用户。
 */
@Service
public class AgentSsoService {

    private static final String PREFIX = "agent:sso:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentPrincipalResolver resolver;
    private final AgentLevelService levels;
    private final String agentPublicUrl;

    public AgentSsoService(StringRedisTemplate redis, ObjectMapper objectMapper,
                           AgentPrincipalResolver resolver, AgentLevelService levels,
                           @Value("${app.agent.public-url:http://localhost:8501}") String agentPublicUrl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.resolver = resolver;
        this.levels = levels;
        this.agentPublicUrl = agentPublicUrl;
    }

    public record CodeData(String code, String expiresAt, String agentUrl) {
    }

    public record Exchanged(String token, Map<String, Object> profile) {
    }

    public CodeData issue(String userToken, long accountId, String role) {
        if (userToken == null || userToken.isBlank()) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "缺少登录令牌");
        }
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", userToken);
        payload.put("accountId", accountId);
        payload.put("role", role);
        try {
            redis.opsForValue().set(PREFIX + code, objectMapper.writeValueAsString(payload), TTL);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SSO code 序列化失败", e);
        }
        String url = agentPublicUrl.endsWith("/") ? agentPublicUrl : agentPublicUrl + "/";
        return new CodeData(code, LocalDateTime.now().plus(TTL).toString(), url + "?code=" + code);
    }

    @SuppressWarnings("unchecked")
    public Exchanged exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "缺少 code");
        }
        String raw = redis.opsForValue().getAndDelete(PREFIX + code.trim());
        if (raw == null) {
            // 过期、已用、伪造都归一到同一个提示：不给试探的空间
            throw new BizException(ErrorCode.TOKEN_INVALID, "code 已失效，请从管理端重新打开");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(raw, Map.class);
            String token = String.valueOf(payload.get("token"));
            return new Exchanged(token, profileOf(token));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.TOKEN_INVALID, "code 已失效，请从管理端重新打开");
        }
    }

    /**
     * 组装交给 Agent 界面的身份快照。
     *
     * <p><b>数据范围必须在这里带上</b>：侧栏的「班级 N 个 / 课程 M 个」直接读
     * {@code classIds/courseIds}，缺了它们，教师看到的永远是「0 个」——明明有权查，
     * 界面上却像没接通任何数据，这类"看起来坏了"比真坏了更难查。
     *
     * <p>快照只用于展示：工具调用的鉴权仍走 {@code X-User-Token} 由
     * {@link AgentPrincipalResolver} 现算，不信任这里的副本。
     */
    private Map<String, Object> profileOf(String token) {
        AgentPrincipal principal = resolver.resolve(token);
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("accountId", principal.accountId());
        profile.put("role", principal.user().role());
        profile.put("teacherId", principal.teacherId());
        profile.put("levels", levels.levels());
        profile.put("classIds", List.copyOf(principal.classIds()));
        profile.put("courseIds", List.copyOf(principal.courseIds()));
        return profile;
    }
}
