package com.agent.orchestrator.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private final Map<String, int[]> requestCounts = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS = 100;
    private static final long WINDOW_MS = 60_000;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIP(request);
        long now = System.currentTimeMillis();

        int[] record = requestCounts.compute(ip, (key, existing) -> {
            if (existing == null || now - existing[0] > WINDOW_MS) {
                return new int[]{ (int)now, 1 };
            }
            existing[1]++;
            return existing;
        });

        if (record[1] > MAX_REQUESTS) {
            log.warn("Rate limit exceeded for IP: {}", ip);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limit\",\"message\":\"Too many requests\"}");
            return false;
        }

        return true;
    }

    private String getClientIP(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
