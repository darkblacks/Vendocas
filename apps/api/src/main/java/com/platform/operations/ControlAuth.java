package com.platform.operations;

import com.platform.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates Control Center calls to {@code /internal/**} write and tenant-data endpoints with CONTROL_ADMIN_TOKEN
 * (header {@code X-Control-Admin-Token}, constant-time comparison). When the variable is absent or too short the endpoints
 * answer 404, as if they did not exist. The acting operator comes in {@code X-Control-Actor} and goes to the audit log.
 */
@Component
public class ControlAuth {
    public static final String TOKEN_HEADER = "X-Control-Admin-Token", ACTOR_HEADER = "X-Control-Actor";
    private static final int MIN_TOKEN_LENGTH = 32;
    private final byte[] adminToken;
    private final AuditService audit;

    public ControlAuth(@Value("${CONTROL_ADMIN_TOKEN:}") String adminToken, AuditService audit) {
        this.adminToken = adminToken == null || adminToken.length() < MIN_TOKEN_LENGTH ? null : adminToken.getBytes(StandardCharsets.UTF_8);
        this.audit = audit;
    }

    /** Empty when the call may proceed; otherwise the 404/401 response to return. */
    public Optional<ResponseEntity<?>> reject(String presented) {
        if (adminToken == null) return Optional.of(ResponseEntity.notFound().build());
        if (presented == null || !MessageDigest.isEqual(adminToken, presented.getBytes(StandardCharsets.UTF_8))) {
            return Optional.of(ResponseEntity.status(401).build());
        }
        return Optional.empty();
    }

    public static String actor(String header) {
        String clean = header == null ? "unknown" : header.replaceAll("[^A-Za-z0-9@._+-]", "");
        if (clean.isEmpty()) clean = "unknown";
        return "control-center:" + clean.substring(0, Math.min(150, clean.length()));
    }

    public void audit(String actor, String action, String targetType, Object targetId, Map<String, ?> details) {
        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>(details);
        d.put("actor", actor);
        audit.record(null, null, "PLATFORM_ADMIN", action, targetType, targetId, d, null);
    }
}
