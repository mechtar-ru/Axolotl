package com.agent.orchestrator.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that injects a traceId into MDC for every HTTP request.
 * The traceId flows into all SLF4J log calls within the request thread.
 *
 * Log pattern should include [%X{traceId}] to display it.
 */
@Component
@Order(1)
public class TraceIdFilter implements Filter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        try {
            String traceId = null;
            if (request instanceof HttpServletRequest httpReq) {
                // Propagate incoming trace ID if present (e.g. from frontend or load balancer)
                traceId = httpReq.getHeader(TRACE_ID_HEADER);
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().substring(0, 8);
            }
            MDC.put(MDC_KEY, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
