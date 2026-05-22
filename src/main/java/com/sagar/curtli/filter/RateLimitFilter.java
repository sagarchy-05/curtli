package com.sagar.curtli.filter;

import com.sagar.curtli.service.RateLimiterService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

@RequiredArgsConstructor
public class RateLimitFilter implements Filter {

    private final RateLimiterService limiter;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest http = (HttpServletRequest) req;
        String uri = http.getRequestURI();

        if ("POST".equals(http.getMethod())) {
            boolean isAllowed = true;

            // Get the real client IP from the X-Forwarded-For header,
            // falling back to remote address if the header is empty (e.g., local development)
            String ip = http.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = http.getRemoteAddr();
            } else {
                // X-Forwarded-For can be a comma-separated list if it went through multiple proxies.
                // The first IP in the list is always the original client.
                ip = ip.split(",")[0].trim();
            }

            // Single write endpoint now; /api/bulk-shorten is gone.
            if (uri.equals("/api/shorten") || uri.startsWith("/api/shorten/")) {
                isAllowed = limiter.tryAcquire(ip);
            }

            if (!isAllowed) {
                HttpServletResponse out = (HttpServletResponse) res;
                out.setStatus(429);
                out.setHeader("Retry-After", "60");
                out.setContentType("application/json");
                out.getWriter().write("{\"error\":\"rate_limited\"}");
                return;
            }
        }

        chain.doFilter(req, res);
    }
}