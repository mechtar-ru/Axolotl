package com.agent.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDebugFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(RequestDebugFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String uri = req.getRequestURI();
        String method = req.getMethod();
        String auth = req.getHeader("Authorization") != null ? "present" : "absent";
        log.debug("[RequestDebugFilter] ENTER {} {} auth={}", method, uri, auth);
        try {
            chain.doFilter(request, response);
        } catch (Exception e) {
            log.error("[RequestDebugFilter] filter chain threw for {} {} -> {}", method, uri, e.getMessage(), e);
            throw e;
        } finally {
            log.debug("[RequestDebugFilter] EXIT {} {} status={}", method, uri, res.getStatus());
        }
    }
}
