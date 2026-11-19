package com.platform.contacts;

import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.outbox.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Records business events about a contact (purchase, visit, form, call, reply, custom…) and
 * publishes them for the automation engine. Idempotent by (organization, idempotency key).
 */
@Service
public class ContactEventService {
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;

    public ContactEventService(JdbcTemplate jdbc, OutboxService outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    /** Returns the new event id, or empty if this idempotency key was already recorded. */
    @Transactional
    public Optional<UUID> record(UUID orgId, UUID contactId, String type, Instant occurredAt, String source, String idempotencyKey,
                                 Map<String, ?> properties) {
        UUID id = Ids.newId();
        int n = jdbc.update("""
                INSERT INTO contact_events (id, organization_id, contact_id, type, occurred_at, source, idempotency_key, properties)
                VALUES (?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING""",
                id, orgId, contactId, type, Timestamp.from(occurredAt), source, idempotencyKey, Json.write(properties == null ? Map.of() : properties));
        if (n == 0) return Optional.empty();
        if (contactId != null) touch(contactId, type, occurredAt);
        Map<String, Object> payload = new HashMap<>(properties == null ? Map.of() : properties);
        payload.put("eventId", id.toString());
        payload.put("type", type);
        payload.put("occurredAt", occurredAt.toString());
        if (contactId != null) payload.put("contactId", contactId.toString());
        outbox.publish(orgId, "contact_event", id, "contact.event", payload);
        return Optional.of(id);
    }

    private void touch(UUID contactId, String type, Instant at) {
        Timestamp t = Timestamp.from(at);
        switch (type) {
            case "PURCHASE_COMPLETED" -> jdbc.update("UPDATE contacts SET last_purchase_at = GREATEST(COALESCE(last_purchase_at, ?), ?), last_interaction_at = GREATEST(COALESCE(last_interaction_at, ?), ?) WHERE id = ?", t, t, t, t, contactId);
            case "VISIT", "SERVICE_COMPLETED" -> jdbc.update("UPDATE contacts SET last_visit_at = GREATEST(COALESCE(last_visit_at, ?), ?), last_interaction_at = GREATEST(COALESCE(last_interaction_at, ?), ?) WHERE id = ?", t, t, t, t, contactId);
            case "REPLY_RECEIVED", "FORM_SUBMITTED", "CALL_ANSWERED", "PAGE_VIEW", "CHECKOUT_STARTED" ->
                    jdbc.update("UPDATE contacts SET last_interaction_at = GREATEST(COALESCE(last_interaction_at, ?), ?) WHERE id = ?", t, t, contactId);
            default -> { }
        }
    }
}
