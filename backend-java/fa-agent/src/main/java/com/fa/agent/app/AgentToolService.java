package com.fa.agent.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fa.agent.api.dto.AgentDtos;
import com.fa.agent.app.tools.ToolCtx;
import com.fa.agent.app.tools.ToolSpec;
import com.fa.common.error.BizException;
import com.fa.common.error.ErrorCode;
import com.fa.common.web.TraceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 工具执行编排：能力级别 → 授权 → 两阶段确认 → 执行 → 留痕 → 撤销。
 *
 * <p>这个类是 §11.5「五道闸」的唯一落地点。刻意<b>不</b>把闸分散到各工具里：
 * 14 个工具各写一遍令牌校验，必然有一个漏；写在一处，评审只需看这一个文件。
 *
 * <p>两个容易做错、这里专门处理的点：
 * <ol>
 *   <li><b>预览与执行是两次独立的 plan() 调用</b>。执行前重算一次 before 快照，
 *       保证写进日志的「变更前」是执行那一刻的真实状态，而不是几分钟前预览时的状态。</li>
 *   <li><b>幂等键在消费令牌之前查</b>。断点恢复会重放同一个 toolCallId，
 *       如果先消费令牌，重放会因为「令牌已用过」报 FA-6002，用户会以为操作失败了。</li>
 * </ol>
 */
@Service
public class AgentToolService {

    private static final Logger log = LoggerFactory.getLogger(AgentToolService.class);
    private static final String TOOLCALL_PREFIX = "agent:toolcall:";
    private static final Duration TOOLCALL_TTL = Duration.ofMinutes(10);

    private final ToolRegistry registry;
    private final AgentLevelService levels;
    private final ConfirmTokenService tokens;
    private final AgentLogService logs;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration undoWindow;

    public AgentToolService(ToolRegistry registry, AgentLevelService levels,
                            ConfirmTokenService tokens, AgentLogService logs,
                            StringRedisTemplate redis, ObjectMapper objectMapper,
                            @Value("${app.agent.undo-window-seconds:1800}") long undoWindowSeconds) {
        this.registry = registry;
        this.levels = levels;
        this.tokens = tokens;
        this.logs = logs;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.undoWindow = Duration.ofSeconds(undoWindowSeconds);
    }

    // ============================ catalog ============================

    public AgentDtos.CatalogData catalog(AgentPrincipal principal) {
        requireRole(principal);
        AgentDtos.CatalogScope scope = new AgentDtos.CatalogScope(
                List.copyOf(principal.classIds()), List.copyOf(principal.courseIds()));
        return new AgentDtos.CatalogData(levels.levels(), scope, registry.catalog());
    }

    // ============================ invoke ============================

    public AgentDtos.ToolData invoke(AgentPrincipal principal, String tool,
                                     Map<String, Object> params, String confirmToken,
                                     String toolCallId, String conversationId) {
        requireRole(principal);
        ToolSpec spec = registry.require(tool);
        if (!levels.enabled(spec.level())) {
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED,
                    "该能力未开启（" + spec.level() + "），请联系管理员");
        }
        ToolCtx ctx = new ToolCtx(principal, conversationId, TraceContext.currentTraceId());

        if (!spec.requiresConfirm()) {
            return runRead(ctx, spec, params);
        }
        if (confirmToken == null || confirmToken.isBlank()) {
            return preview(ctx, spec, params);
        }
        return commit(ctx, spec, params, confirmToken, toolCallId);
    }

    private AgentDtos.ToolData runRead(ToolCtx ctx, ToolSpec spec, Map<String, Object> params) {
        long started = System.nanoTime();
        try {
            AgentDtos.ToolResult result = spec.read().read(ctx, params);
            long ms = (System.nanoTime() - started) / 1_000_000;
            logs.writeQuery(ctx.principal().accountId(), ctx.conversationId(), spec.name(), params,
                    result.count(), ms, true, null, ctx.traceId());
            return new AgentDtos.ToolData(false, null, null, null, result.data(), null);
        } catch (BizException e) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            logs.writeQuery(ctx.principal().accountId(), ctx.conversationId(), spec.name(), params,
                    0, ms, false, e.errorCode().code(), ctx.traceId());
            throw e;
        }
    }

    private AgentDtos.ToolData preview(ToolCtx ctx, ToolSpec spec, Map<String, Object> params) {
        ToolSpec.Plan plan = spec.plan().plan(ctx, params);
        ConfirmTokenService.Issued issued = tokens.issue(
                ctx.principal().accountId(), spec.name(), params);
        AgentDtos.Preview preview = withUndoUntil(plan.preview());
        log.info("agent-preview tool={} user={} affected={}",
                spec.name(), ctx.principal().accountId(), preview.affectedCount());
        return new AgentDtos.ToolData(true, preview, issued.token(),
                AgentDtos.iso(issued.expiresAt()), null, null);
    }

    private AgentDtos.ToolData commit(ToolCtx ctx, ToolSpec spec, Map<String, Object> params,
                                      String confirmToken, String toolCallId) {
        String cached = readToolCall(ctx.principal().accountId(), toolCallId);
        if (cached != null) {
            // 断点重放：直接把上次的结果再返回一次，不重复执行、也不报「令牌已用过」
            return deserialize(cached);
        }

        // 先重算一次预览：拿到「执行这一刻」的 before 快照与影响条数
        ToolSpec.Plan plan = spec.plan().plan(ctx, params);
        String paramsHash = tokens.consume(
                ctx.principal().accountId(), spec.name(), confirmToken, params);

        AgentDtos.ToolResult result = spec.exec().exec(ctx, params);

        LocalDateTime undoableUntil = plan.preview().undoable()
                ? LocalDateTime.now().plus(undoWindow) : null;
        long actionLogId = logs.writeAction(
                ctx.principal().accountId(), ctx.conversationId(), spec.name(), params,
                paramsHash, ConfirmTokenService.fingerprint(confirmToken),
                plan.before(), result.data(), plan.preview().affectedCount(),
                plan.preview().undoable(), undoableUntil, ctx.traceId());

        AgentDtos.ToolData data = new AgentDtos.ToolData(false, withUndoUntil(plan.preview()),
                null, null, result.data(), actionLogId);
        writeToolCall(ctx.principal().accountId(), toolCallId, data);
        log.info("agent-executed tool={} user={} logId={}",
                spec.name(), ctx.principal().accountId(), actionLogId);
        return data;
    }

    // ============================ undo ============================

    public AgentDtos.UndoData undo(AgentPrincipal principal, long actionLogId) {
        requireRole(principal);
        AgentLogService.ActionLog entry = logs.findAction(actionLogId);
        if (entry == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "操作记录不存在");
        }
        if (entry.userId() != principal.accountId()) {
            // 撤销别人的操作等于越权写入，按数据范围拒绝
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED, "只能撤销自己的操作");
        }
        if (entry.undoneAt() != null) {
            throw new BizException(ErrorCode.UNDO_UNSUPPORTED, "该操作已撤销过");
        }
        if (!entry.undoable()) {
            throw new BizException(ErrorCode.UNDO_UNSUPPORTED);
        }
        if (entry.undoableUntil() == null || LocalDateTime.now().isAfter(entry.undoableUntil())) {
            throw new BizException(ErrorCode.UNDO_UNSUPPORTED, "已超过 30 分钟撤销窗口，需人工处理");
        }

        ToolSpec spec = registry.require(entry.tool());
        if (spec.undo() == null) {
            throw new BizException(ErrorCode.UNDO_UNSUPPORTED);
        }
        Map<String, Object> params = logs.readMap(entry.paramsJson());
        ToolCtx ctx = new ToolCtx(principal, entry.conversationId(), TraceContext.currentTraceId());
        spec.undo().undo(ctx, params, logs.readTree(entry.beforeJson()),
                logs.readTree(entry.afterJson()));

        long undoLogId = logs.writeUndoLog(principal.accountId(), entry.conversationId(),
                spec.name(), params, entry.afterJson(), entry.beforeJson(), ctx.traceId());
        logs.markUndone(actionLogId, undoLogId, principal.accountId());
        log.info("agent-undone original={} undoneLog={}", actionLogId, undoLogId);
        return new AgentDtos.UndoData(true, undoLogId, actionLogId, "已撤销");
    }

    // ============================ 辅助 ============================

    private void requireRole(AgentPrincipal principal) {
        if (!principal.canUseAgent()) {
            throw new BizException(ErrorCode.AGENT_SCOPE_DENIED, "当前角色不能使用智能助手");
        }
    }

    private AgentDtos.Preview withUndoUntil(AgentDtos.Preview p) {
        if (!p.undoable()) {
            return p;
        }
        return new AgentDtos.Preview(p.actionLabel(), p.resourceLabel(), p.affectedCount(),
                p.affectedSummary(), p.fieldDiffs(), true,
                AgentDtos.iso(LocalDateTime.now().plus(undoWindow)), p.irreversibleHint());
    }

    private String toolCallKey(long userId, String toolCallId) {
        return TOOLCALL_PREFIX + userId + ":" + toolCallId;
    }

    private String readToolCall(long userId, String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        try {
            return redis.opsForValue().get(toolCallKey(userId, toolCallId));
        } catch (RuntimeException e) {
            log.warn("toolcall-cache-read-failed，按未命中处理", e);
            return null;
        }
    }

    private void writeToolCall(long userId, String toolCallId, AgentDtos.ToolData data) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        try {
            redis.opsForValue().set(toolCallKey(userId, toolCallId),
                    objectMapper.writeValueAsString(data), TOOLCALL_TTL);
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("toolcall-cache-write-failed，幂等保护本次失效", e);
        }
    }

    private AgentDtos.ToolData deserialize(String json) {
        try {
            return objectMapper.readValue(json, AgentDtos.ToolData.class);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "补偿结果无法解析，请重新发起");
        }
    }

    /** 供控制器组装 catalog 用：把 Boolean map 转成契约里的 {L0:true,...} 形态。 */
    public Map<String, Boolean> levelFlags() {
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        ordered.put("L0", levels.enabled("L0"));
        ordered.put("L1", levels.enabled("L1"));
        ordered.put("L2", levels.enabled("L2"));
        ordered.put("L3", levels.enabled("L3"));
        return ordered;
    }
}
