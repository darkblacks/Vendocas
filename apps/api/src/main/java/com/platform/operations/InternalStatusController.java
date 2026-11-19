package com.platform.operations;

import com.platform.shared.AppProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operational status for the Vendocas Control Center (homologation Codespace).
 *
 * <p>Protected by a dedicated shared secret ({@code CONTROL_STATUS_TOKEN}, header {@code X-Control-Token}); when the
 * variable is not set the endpoint does not exist (404). It never returns credentials, tenant data or stack traces —
 * only counters, versions and short error summaries.
 */
@RestController
public class InternalStatusController {
    private static final int MIN_TOKEN_LENGTH = 32;
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final AppProperties props;
    private final byte[] token;

    public InternalStatusController(JdbcTemplate jdbc, RedisConnectionFactory redis, AppProperties props,
                                    @Value("${CONTROL_STATUS_TOKEN:}") String token) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.props = props;
        this.token = token == null || token.length() < MIN_TOKEN_LENGTH ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader(value = "X-Control-Token", required = false) String presented) {
        if (token == null) return ResponseEntity.notFound().build();
        if (presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> database = database();
        Map<String, Object> redisStatus = redis();
        body.put("status", Boolean.TRUE.equals(database.get("up")) && Boolean.TRUE.equals(redisStatus.get("up")) ? "UP" : "DEGRADED");
        body.put("gitCommit", System.getenv().getOrDefault("VENDOCAS_GIT_COMMIT", null));
        body.put("startedAt", Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString());
        body.put("database", database);
        body.put("redis", redisStatus);
        body.put("workers", Boolean.TRUE.equals(database.get("up")) ? workers() : Map.of("enabled", props.workers().enabled()));
        body.put("recentErrors", Boolean.TRUE.equals(database.get("up")) ? recentErrors() : List.of());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private Map<String, Object> database() {
        Map<String, Object> m = new LinkedHashMap<>();
        long t = System.nanoTime();
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            m.put("up", true);
            m.put("latencyMs", (System.nanoTime() - t) / 1_000_000);
            m.put("flywayVersion", jdbc.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",
                    String.class).stream().findFirst().orElse(null));
        } catch (RuntimeException e) {
            m.put("up", false);
        }
        return m;
    }

    private Map<String, Object> redis() {
        try (var c = redis.getConnection()) {
            String pong = c.ping();
            return Map.of("up", "PONG".equalsIgnoreCase(pong));
        } catch (RuntimeException e) {
            return Map.of("up", false, "error", e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> workers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.workers().enabled());
        m.put("outboxPending", count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL"));
        m.put("jobsPending", count("SELECT count(*) FROM scheduled_jobs WHERE status IN ('PENDING','RUNNING')"));
        m.put("jobsDead", count("SELECT count(*) FROM scheduled_jobs WHERE status = 'DEAD'"));
        m.put("messagesQueued", count("SELECT count(*) FROM messages WHERE status IN ('CREATED','QUEUED')"));
        m.put("automationDue", count("SELECT count(*) FROM automation_instances WHERE state IN ('PENDING','WAITING','READY') AND next_run_at <= now()"));
        m.put("deadLetters", count("SELECT count(*) FROM automation_instances WHERE state = 'DEAD_LETTER'"));
        return m;
    }

    private long count(String sql) {
        try {
            Long n = jdbc.queryForObject(sql, Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private List<Map<String, Object>> recentErrors() {
        try {
            return jdbc.queryForList("""
                    SELECT at, source, message FROM (
                      SELECT updated_at AS at, 'job:' || job_type AS source, last_error AS message FROM scheduled_jobs
                       WHERE last_error IS NOT NULL AND updated_at > now() - interval '24 hours'
                      UNION ALL
                      SELECT created_at, 'outbox:' || event_type, last_error FROM outbox_events
                       WHERE last_error IS NOT NULL AND created_at > now() - interval '24 hours'
                    ) e ORDER BY at DESC LIMIT 10""").stream()
                    .map(r -> Map.<String, Object>of(
                            "at", String.valueOf(r.get("at")),
                            "source", String.valueOf(r.get("source")),
                            "message", truncate(String.valueOf(r.get("message")))))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
