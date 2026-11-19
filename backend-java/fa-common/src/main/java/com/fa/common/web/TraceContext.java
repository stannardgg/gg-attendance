package com.fa.common.web;

import org.slf4j.MDC;

/**
 * traceId 的读写口。刻意只依赖 slf4j，不碰 servlet——
 * 这样 fa-infra、fa-org 这类不引 web 的模块也能写审计日志带上 traceId，
 * 否则为了一个字符串常量就得把 jakarta.servlet 拖进依赖树。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    public static String currentTraceId() {
        String v = MDC.get(MDC_KEY);
        return v == null ? "" : v;
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
