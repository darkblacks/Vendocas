package com.platform.messaging.providers;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal per-provider circuit breaker: after {@code THRESHOLD} consecutive retryable failures the
 * circuit opens for {@code OPEN_FOR}; messages stay QUEUED (not failed) while open, so one provider
 * outage never cascades into other channels or into lost messages.
 */
@Component
public class CircuitBreaker {
    static final int THRESHOLD = 10;
    static final Duration OPEN_FOR = Duration.ofSeconds(60);
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        int consecutiveFailures;
        Instant openUntil = Instant.EPOCH;
    }

    public boolean allow(String provider) {
        State s = states.computeIfAbsent(provider, k -> new State());
        synchronized (s) { return Instant.now().isAfter(s.openUntil); }
    }

    public void success(String provider) {
        State s = states.computeIfAbsent(provider, k -> new State());
        synchronized (s) { s.consecutiveFailures = 0; }
    }

    public void failure(String provider) {
        State s = states.computeIfAbsent(provider, k -> new State());
        synchronized (s) {
            if (++s.consecutiveFailures >= THRESHOLD) {
                s.openUntil = Instant.now().plus(OPEN_FOR);
                s.consecutiveFailures = 0;
            }
        }
    }

    public boolean isOpen(String provider) { return !allow(provider); }
}
