package com.sagar.curtli.filter;

import com.sagar.curtli.service.RateLimitResult;
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
            // Get the real client IP from the X-Forwarded-For header, falling
            // back to remote address if the header is empty (e.g., local dev).
            String ip = http.getHeader("X-Forwarded-For");
            if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                ip = http.getRemoteAddr();
            } else {
                // XFF can be a comma-separated list when it went through multiple
                // proxies. The first IP in the list is always the original client.
                ip = ip.split(",")[0].trim();
            }

            // Single write endpoint now; /api/bulk-shorten is gone.
            if (uri.equals("/api/shorten") || uri.startsWith("/api/shorten/")) {
                RateLimitResult result = limiter.tryAcquire(ip);

                if (result == RateLimitResult.DENIED) {
                    writeJsonError((HttpServletResponse) res, 429, 60,
                            "{\"error\":\"rate_limited\"}");
                    return;
                }
                if (result == RateLimitResult.UNAVAILABLE) {
                    // Redis errored — fail closed with a structured 503 so the
                    // client backs off via Retry-After rather than retrying
                    // immediately and amplifying the underlying problem. The
                    // 503 also gives ops a clean signal in metrics ("spike in
                    // 503s on /api/shorten" = Redis is in trouble), distinct
                    // from the generic 500s a propagated exception would produce.
                    writeJsonError((HttpServletResponse) res, 503, 15,
                            "{\"error\":\"service_degraded\","
                            + "\"message\":\"Rate limiter temporarily unavailable. Please retry shortly.\"}");
                    return;
                }
                // ALLOWED → fall through to the filter chain.
            }
        }

        chain.doFilter(req, res);
    }

    private static void writeJsonError(HttpServletResponse res, int status,
                                       int retryAfterSeconds, String json) throws IOException {
        res.setStatus(status);
        res.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        res.setContentType("application/json");
        res.getWriter().write(json);
    }
}