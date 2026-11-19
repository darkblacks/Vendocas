package com.platform.messaging;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
@Tag(name = "messages")
public class MessageController {
    private final JdbcTemplate jdbc;

    public MessageController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record MessageRow(UUID id, UUID contactId, String contactName, String direction, String channel, String purpose, String status,
                             String statusReason, String originType, UUID originId, boolean test, long costMinor, String currency, Instant createdAt) {}

    @GetMapping
    @RequiresPermission(Permission.REPORTS_READ)
    public List<MessageRow> list(@RequestParam(required = false) String status, @RequestParam(required = false) String channel,
                                 @RequestParam(required = false) UUID contactId, @RequestParam(required = false) String originType,
                                 @RequestParam(required = false) UUID originId, @RequestParam(defaultValue = "100") int limit) {
        return jdbc.query("""
                SELECT m.id, m.contact_id, c.name, m.direction, m.channel, m.purpose, m.status, m.status_reason, m.origin_type, m.origin_id,
                       m.is_test, m.cost_charged_minor, m.cost_currency, m.created_at
                FROM messages m LEFT JOIN contacts c ON c.id = m.contact_id
                WHERE m.organization_id = ? AND (?::text IS NULL OR m.status = ?) AND (?::text IS NULL OR m.channel = ?)
                  AND (?::uuid IS NULL OR m.contact_id = ?) AND (?::text IS NULL OR m.origin_type = ?) AND (?::uuid IS NULL OR m.origin_id = ?)
                ORDER BY m.created_at DESC LIMIT ?""",
                (rs, i) -> new MessageRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class), rs.getBoolean(11),
                        rs.getLong(12), rs.getString(13), rs.getTimestamp(14).toInstant()),
                TenantContext.current().orgId(), status, status, channel, channel, contactId, contactId, originType, originType, originId, originId,
                Math.min(Math.max(limit, 1), 500));
    }
}
