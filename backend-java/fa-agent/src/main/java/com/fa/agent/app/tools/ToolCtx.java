package com.fa.agent.app.tools;

import com.fa.agent.app.AgentPrincipal;

/**
 * 工具调用上下文。
 *
 * <p>只放「这一次调用是谁发起的、属于哪个会话」，不放 Service——
 * Service 由各工具自己的构造器注入，避免上下文变成一个隐式的服务定位器。
 */
public record ToolCtx(AgentPrincipal principal, String conversationId, String traceId) {
}
