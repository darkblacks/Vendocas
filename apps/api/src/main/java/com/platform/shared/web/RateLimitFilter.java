package com.platform.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/** Coarse per-IP limits on unauthenticated surfaces (auth, tracking, public forms, webhooks). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        String ip = ClientIp.of(req);
        int limit = 0;
        String bucket = null;
        if (path.startsWith("/api/v1/auth/")) { limit = 30; bucket = "auth:" + ip; }
        else if (path.startsWith("/api/v1/track")) { limit = 600; bucket = "track:" + ip; }
        else if (path.startsWith("/api/v1/public/")) { limit = 60; bucket = "public:" + ip; }
        else if (path.startsWith("/api/v1/")) { limit = 1200; bucket = "api:" + ip; }
        if (bucket != null && !limiter.tryAcquire(bucket, limit, Duration.ofMinutes(1))) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"status\":429,\"code\":\"RATE_LIMITED\",\"detail\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
