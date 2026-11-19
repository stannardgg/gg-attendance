package com.fa.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 给每个请求分配 traceId，写进 MDC 与响应头。
 *
 * <p>为什么自己生成而不是依赖网关透传：本地开发和 CI 里没有网关，
 * 而排查线上问题时最想要的就是「用户截图里的 traceId 能直接 grep 到日志」。
 * 上游若已带 X-Trace-Id 则沿用，保证跨服务可串。
 */
public class TraceIdFilter extends OncePerRequestFilter {


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(TraceContext.HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        TraceContext.set(traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

}
