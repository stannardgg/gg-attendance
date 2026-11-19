package com.fa.agent.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具端点的请求/响应结构。字段逐字对齐
 * {@code contracts/openapi/internal.yaml}（ToolCatalogItem / AgentToolReq / AgentToolData）。
 *
 * <p><b>为什么不复用 java-api 的 DTO</b>：这一组是给机器（Agent 进程）看的，
 * 不是给浏览器看的。混用会让「前端改字段」意外改动工具契约，而工具契约是冻结的。
 */
public final class AgentDtos {

    private AgentDtos() {
    }

    /** 统一信封。契约里的 Result 只有 code/message/traceId，data 由各端点自行给出。 */
    public record Envelope<T>(String code, String message, T data, String traceId) {
    }

    public record ToolReq(Map<String, Object> params, String confirmToken, String toolCallId) {
    }

    public record FieldDiff(String field, String label, String before, String after) {
    }

    /**
     * 确认卡要渲染的全部内容（§11.5：少一项即不合格）。
     * {@code undoableUntil} 用 ISO-8601 字符串，避免 Agent 侧再猜时区。
     */
    public record Preview(String actionLabel,
                          String resourceLabel,
                          int affectedCount,
                          String affectedSummary,
                          List<FieldDiff> fieldDiffs,
                          boolean undoable,
                          String undoableUntil,
                          String irreversibleHint) {
    }

    public record ToolData(boolean requiresConfirm,
                           Preview preview,
                           String confirmToken,
                           String expiresAt,
                           Object result,
                           Long actionLogId) {
    }

    public record CatalogItem(String name,
                              String level,
                              String title,
                              String description,
                              boolean requiresConfirm,
                              boolean undoable,
                              Map<String, Object> paramsSchema,
                              Map<String, Object> returnsSchema) {
    }

    public record CatalogScope(List<Long> classIds, List<Long> courseIds) {
    }

    public record CatalogData(Map<String, Boolean> level, CatalogScope scope, List<CatalogItem> tools) {
    }

    /** 工具执行结果：data 给 Agent，count 用于回答末尾的「命中 N 条」。 */
    public record ToolResult(Object data, int count) {
        public static ToolResult of(Object data, int count) {
            return new ToolResult(data, count);
        }

        public static ToolResult single(Object data) {
            return new ToolResult(data, 1);
        }
    }

    /** 撤销响应：与执行同构，Agent 不需要为撤销写第二套解析。 */
    public record UndoData(boolean undone, Long undoneLogId, Long originalLogId, String message) {
    }

    /** 内部传参用的时间格式统一入口，避免各处 LocalDateTime.toString 的精度差异。 */
    public static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
