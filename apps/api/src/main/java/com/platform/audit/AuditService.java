package com.platform.audit;

import com.platform.shared.Ids;
import com.platform.shared.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/** Append-only audit trail. Callers must never pass secrets or full payloads in {@code details}. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void record(UUID orgId, UUID actorUserId, String actorType, String action, String targetType, Object targetId,
                       Map<String, ?> details, String ip) {
        jdbc.update("""
                INSERT INTO audit_logs (id, organization_id, actor_user_id, actor_type, action, target_type, target_id, ip_address, details)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb)""",
                Ids.newId(), orgId, actorUserId, actorType, action, targetType, targetId == null ? null : targetId.toString(), ip,
                Json.write(details == null ? Map.of() : details));
    }

    public void user(UUID orgId, UUID userId, String action, String targetType, Object targetId, Map<String, ?> details) {
        record(orgId, userId, "USER", action, targetType, targetId, details, null);
    }

    public void system(UUID orgId, String action, String targetType, Object targetId, Map<String, ?> details) {
        record(orgId, null, "SYSTEM", action, targetType, targetId, details, null);
    }
}
