package com.agent.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class SecurityDebugEntryPoint implements AuthenticationEntryPoint {
    private static final Logger log = LoggerFactory.getLogger(SecurityDebugEntryPoint.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException authException)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.warn("[AuthEntryPoint] UNAUTHORIZED {} {} authPresent={} principal={} message={}",
                request.getMethod(), request.getRequestURI(), auth != null, auth != null ? auth.getName() : "-",
                authException != null ? authException.getMessage() : "null");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }
}
