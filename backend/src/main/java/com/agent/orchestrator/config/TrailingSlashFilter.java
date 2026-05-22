package com.agent.orchestrator.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Strips trailing slashes from request URIs before they reach Spring Security.
 *
 * Spring Boot 3.x disables trailing-slash matching by default.  Spring Security's
 * MvcRequestMatcher uses the MVC PathPattern parser which does not support trailing
 * slashes at all.  This filter normalizes the URI so that /api/schemas/ is treated
 * as /api/schemas for both security authorization and handler mapping purposes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TrailingSlashFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        String uri = request.getRequestURI();

        // Only strip trailing slash if the path has more than just "/"
        if (uri.length() > 1 && uri.endsWith("/")) {
            filterChain.doFilter(new TrailingSlashRequestWrapper(request), servletResponse);
        } else {
            filterChain.doFilter(request, servletResponse);
        }
    }

    /**
     * Wraps the request to strip trailing slashes from getRequestURI() and getServletPath().
     */
    private static class TrailingSlashRequestWrapper extends HttpServletRequestWrapper {

        TrailingSlashRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getRequestURI() {
            String uri = super.getRequestURI();
            if (uri.length() > 1 && uri.endsWith("/")) {
                return uri.substring(0, uri.length() - 1);
            }
            return uri;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = super.getRequestURL();
            if (url.length() > 1 && url.charAt(url.length() - 1) == '/') {
                return new StringBuffer(url.substring(0, url.length() - 1));
            }
            return url;
        }

        @Override
        public String getServletPath() {
            String path = super.getServletPath();
            if (path.length() > 1 && path.endsWith("/")) {
                return path.substring(0, path.length() - 1);
            }
            return path;
        }

        @Override
        public String getPathInfo() {
            String path = super.getPathInfo();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                return path.substring(0, path.length() - 1);
            }
            return path;
        }
    }
}
