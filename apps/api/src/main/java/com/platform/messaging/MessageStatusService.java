package com.platform.messaging;

import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.outbox.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Applies provider delivery events. Only states the provider actually reported are recorded, and
 * status only moves forward (webhooks can arrive out of order): SENT < DELIVERED < READ < REPLIED.
 * FAILED is applied unless the message was already confirmed delivered.
 */
@Service
public class MessageStatusService {
    private static final Map<String, Integer> RANK = Map.of("QUEUED", 1, "SUBMITTED", 2, "SENT", 3, "DELIVERED", 4, "READ", 5, "REPLIED", 6);
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;

    public MessageStatusService(JdbcTemplate jdbc, OutboxService outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public record Ref(UUID id, UUID orgId, UUID contactId, String status, String channel, String to) {}

    public java.util.Optional<Ref> byProviderId(String provider, String providerMessageId) {
        return jdbc.query("SELECT id, organization_id, contact_id, status, channel, to_address FROM messages WHERE provider = ? AND provider_message_id = ?",
                (rs, i) -> new Ref(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getString(6)), provider, providerMessageId).stream().findFirst();
    }

    /** eventType: SENT, DELIVERED, READ, FAILED, BOUNCED, COMPLAINED, CLICKED. Returns the message ref if found. */
    @Transactional
    public java.util.Optional<Ref> apply(String provider, String providerMessageId, String eventType, Instant at, String providerEventId, Map<String, ?> detail) {
        var ref = byProviderId(provider, providerMessageId);
        if (ref.isEmpty()) return ref;
        Ref m = ref.get();
        int n = jdbc.update("""
                INSERT INTO message_events (id, organization_id, message_id, type, provider_event_id, occurred_at, detail)
                VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING""", Ids.newId(), m.orgId(), m.id(), eventType,
                providerEventId == null ? eventType + ":" + providerMessageId : providerEventId, Timestamp.from(at), Json.write(detail == null ? Map.of() : detail));
        if (n == 0) return ref; // duplicate event
        String target = switch (eventType) {
            case "SENT" -> "SENT";
            case "DELIVERED" -> "DELIVERED";
            case "READ" -> "READ";
            case "FAILED", "BOUNCED" -> "FAILED";
            default -> null;
        };
        if (target == null) return ref;
        int current = RANK.getOrDefault(m.status(), 0);
        if ("FAILED".equals(target)) {
            if (current >= RANK.get("DELIVERED") || "FAILED".equals(m.status())) return ref;
            jdbc.update("UPDATE messages SET status = 'FAILED', status_reason = ?, failed_at = ?, updated_at = now() WHERE id = ?",
                    detail == null ? eventType : String.valueOf(detail.containsKey("reason") ? detail.get("reason") : eventType), Timestamp.from(at), m.id());
        } else if (RANK.get(target) > current) {
            String col = switch (target) { case "DELIVERED" -> "delivered_at"; case "READ" -> "read_at"; default -> "sent_at"; };
            jdbc.update("UPDATE messages SET status = ?, " + col + " = COALESCE(" + col + ", ?), updated_at = now() WHERE id = ?", target, Timestamp.from(at), m.id());
        }
        outbox.publish(m.orgId(), "message", m.id(), "message.status", Map.of("messageId", m.id().toString(), "status", target,
                "contactId", m.contactId() == null ? "" : m.contactId().toString()));
        return ref;
    }
}
