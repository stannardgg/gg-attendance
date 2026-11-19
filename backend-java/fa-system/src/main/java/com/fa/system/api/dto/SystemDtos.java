package com.fa.system.api.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** admin-system 三个端点（概览 / 审计 / 配置）的出入参，字段名与 java-api.yaml 逐字对齐。 */
public final class SystemDtos {

    private SystemDtos() {
    }

    // ---------------------------------------------------------------- 概览

    /**
     * 概览指标。
     *
     * @param todayRate (PRESENT + LATE) / 应到；LEAVE/EXEMPT 进分母不进分子（契约原文）
     * @param trend     近 7 天，按日期升序
     */
    public record Overview(
            int todaySessions,
            int todayExpected,
            int todayPresent,
            double todayRate,
            int pendingAppeals,
            List<TrendPoint> trend) {
    }

    public record TrendPoint(String date, double rate, int expected, int present) {
    }

    // ---------------------------------------------------------------- 审计

    public record AuditLog(
            long id,
            Long actorId,
            String actorName,
            String actorRole,
            String action,
            String targetType,
            Long targetId,
            Map<String, Object> before,
            Map<String, Object> after,
            String clientIp,
            String traceId,
            LocalDateTime createdAt) {
    }

    // ---------------------------------------------------------------- 配置

    public record ConfigItem(
            String configKey,
            String configValue,
            String valueType,
            String category,
            String description,
            boolean editable,
            LocalDateTime updatedAt) {
    }

    /** 只允许改值，不允许改类型/分类 —— 那些是 DDL 与标定脚本的职责。 */
    public record ConfigUpdateReq(List<Item> items) {
        public record Item(String configKey, String configValue) {
        }
    }
}
