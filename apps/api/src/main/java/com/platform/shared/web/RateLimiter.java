package com.platform.shared.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiter backed by Redis (shared across instances). If Redis is unavailable
 * it degrades to a per-instance in-memory window rather than failing open entirely.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<String, Window> local = new ConcurrentHashMap<>();

    public RateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    public boolean tryAcquire(String bucket, int limit, Duration window) {
        long windowId = System.currentTimeMillis() / window.toMillis();
        String key = "rl:" + bucket + ":" + windowId;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, window.plusSeconds(1));
            return count == null || count <= limit;
        } catch (RuntimeException e) {
            log.debug("Redis unavailable for rate limiting, using local window: {}", e.getMessage());
            Window w = local.compute(bucket, (k, v) -> v == null || v.id != windowId ? new Window(windowId) : v);
            if (local.size() > 50_000) local.clear();
            return w.count.incrementAndGet() <= limit;
        }
    }

    private static final class Window {
        final long id;
        final AtomicInteger count = new AtomicInteger();
        Window(long id) { this.id = id; }
    }
}
