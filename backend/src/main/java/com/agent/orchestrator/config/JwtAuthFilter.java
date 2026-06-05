package com.agent.orchestrator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Skip JWT filter for public endpoints (mirror SecurityConfig.permitAll list).
        // Important: if an endpoint is intended to be public, we must not attempt to parse
        // Authorization headers here and accidentally turn a malformed token into a 403.
        return path.startsWith("/api/auth") ||
               path.equals("/api/schemas") || path.startsWith("/api/schemas/") ||
               path.startsWith("/api/health") ||
               path.startsWith("/api/memory") ||
               path.startsWith("/api/graph") ||
               path.startsWith("/api/agents") ||
               path.startsWith("/api/templates") ||
               path.startsWith("/api/history") ||
               path.startsWith("/api/fetch-url") ||
               path.startsWith("/api/share") ||
               path.startsWith("/api/plan") ||
               path.startsWith("/api/plugins") ||
               path.startsWith("/ws") ||
               path.equals("/mcp") ||
               path.startsWith("/actuator") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                       FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        try {
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                if (jwtUtil.isValid(token)) {
                    String username = jwtUtil.getUsername(token);
                    String role = jwtUtil.getRole(token);
                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
                    var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    // invalid token — do not short-circuit the request for permitAll endpoints;
                    // leave security context empty so authorization can proceed normally
                    log.debug("JWT token invalid for request {}", request.getRequestURI());
                }
            }
        } catch (Exception e) {
            // Token parsing can throw (malformed/expired). Log at debug level and do not
            // block public endpoints by throwing here. SecurityConfig controls final decision.
            log.debug("JWT authentication parsing failed for request {}: {}", request.getRequestURI(), e.toString());
        }
        filterChain.doFilter(request, response);
    }
}
