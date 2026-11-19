package com.platform.integrations;

import com.platform.audit.AuditService;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.crypto.Tokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/** Server-to-server API keys: "vk_<prefix>_<secret>"; only prefix + SHA-256 of the full key are stored. */
@Service
public class ApiKeyService {
    public static final Set<String> SCOPES = Set.of("contacts:write", "events:write", "orders:write", "appointments:write", "calls:write");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ApiKeyService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record Created(UUID id, String key) {}

    public record KeyView(UUID id, String name, String prefix, List<String> scopes, String status, Instant lastUsedAt, Instant createdAt) {}

    public record Principal(UUID orgId, UUID keyId, Set<String> scopes) {}

    public Created create(UUID org, UUID userId, String name, List<String> scopes) {
        if (scopes == null || scopes.isEmpty() || !SCOPES.containsAll(scopes)) throw ApiException.badRequest("Invalid scopes");
        String prefix = Tokens.random(6).replaceAll("[^A-Za-z0-9]", "x");
        String key = "vk_" + prefix + "_" + Tokens.random(32);
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO integration_connections (id, organization_id, kind, name, key_prefix, key_hash, scopes, created_by) VALUES (?,?, 'API_KEY', ?,?,?,?,?)",
                id, org, name, prefix, Tokens.sha256Hex(key), String.join(",", scopes), userId);
        audit.user(org, userId, "api_key.created", "api_key", id, Map.of("scopes", scopes));
        return new Created(id, key);
    }

    public List<KeyView> list(UUID org) {
        return jdbc.query("SELECT id, name, key_prefix, scopes, status, last_used_at, created_at FROM integration_connections WHERE organization_id = ? ORDER BY created_at DESC",
                (rs, i) -> new KeyView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), List.of(rs.getString(4).split(",")), rs.getString(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()), org);
    }

    public void revoke(UUID org, UUID userId, UUID id) {
        if (jdbc.update("UPDATE integration_connections SET status = 'REVOKED' WHERE id = ? AND organization_id = ?", id, org) == 0) throw ApiException.notFound("Key");
        audit.user(org, userId, "api_key.revoked", "api_key", id, Map.of());
    }

    public Principal authenticate(String authorization, String scope) {
        if (authorization == null || !authorization.startsWith("Bearer vk_")) throw new ApiException(ErrorCode.UNAUTHENTICATED, "API key required");
        String key = authorization.substring(7).trim();
        String[] parts = key.split("_", 3);
        if (parts.length != 3) throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid API key");
        var rows = jdbc.queryForList("SELECT id, organization_id, key_hash, scopes FROM integration_connections WHERE key_prefix = ? AND status = 'ACTIVE'", parts[1]);
        if (rows.isEmpty() || !Tokens.constantTimeEquals((String) rows.getFirst().get("key_hash"), Tokens.sha256Hex(key))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid API key");
        }
        var r = rows.getFirst();
        Set<String> scopes = Set.of(((String) r.get("scopes")).split(","));
        if (!scopes.contains(scope)) throw new ApiException(ErrorCode.FORBIDDEN, "API key lacks scope " + scope);
        jdbc.update("UPDATE integration_connections SET last_used_at = now() WHERE id = ?", r.get("id"));
        return new Principal((UUID) r.get("organization_id"), (UUID) r.get("id"), scopes);
    }
}
