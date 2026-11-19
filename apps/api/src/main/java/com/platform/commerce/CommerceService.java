package com.platform.commerce;

import com.platform.contacts.ContactEventService;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orders and appointments from trusted sources (server API with API key, signed SDK events,
 * manual entry). Revenue attribution (LAST_TOUCH_7D): a COMPLETED order is attributed to the most
 * recent non-test outbound message the provider accepted for that contact in the 7 days before the
 * order. Attributed revenue is a model, reported separately from confirmed totals.
 */
@Service
public class CommerceService {
    static final Set<String> ORDER_STATUSES = Set.of("PENDING", "COMPLETED", "CANCELED", "REFUNDED");
    private final JdbcTemplate jdbc;
    private final ContactEventService events;

    public CommerceService(JdbcTemplate jdbc, ContactEventService events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public record OrderInput(String externalId, UUID contactId, String status, long amountMinor, String currency, Instant occurredAt) {}

    @Transactional
    public UUID upsertOrder(UUID org, OrderInput o, String source) {
        if (o.externalId() == null || o.externalId().isBlank()) throw ApiException.badRequest("externalId required");
        if (!ORDER_STATUSES.contains(o.status())) throw ApiException.badRequest("Invalid order status");
        if (o.currency() == null || !o.currency().matches("[A-Z]{3}")) throw ApiException.badRequest("Invalid currency");
        if (o.contactId() != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM contacts WHERE id = ? AND organization_id = ?", Integer.class, o.contactId(), org);
            if (n == null || n == 0) throw ApiException.notFound("Contact");
        }
        Instant at = o.occurredAt() == null ? Instant.now() : o.occurredAt();
        var prev = jdbc.queryForList("SELECT status FROM orders WHERE organization_id = ? AND external_id = ?", String.class, org, o.externalId());
        UUID id = jdbc.queryForObject("""
                INSERT INTO orders (id, organization_id, contact_id, external_id, status, amount_minor, currency, source, occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (organization_id, external_id) DO UPDATE SET status = EXCLUDED.status,
                  amount_minor = EXCLUDED.amount_minor, contact_id = COALESCE(EXCLUDED.contact_id, orders.contact_id), updated_at = now()
                RETURNING id""", UUID.class, Ids.newId(), org, o.contactId(), o.externalId(), o.status(), o.amountMinor(), o.currency(), source, Timestamp.from(at));
        boolean newlyCompleted = "COMPLETED".equals(o.status()) && (prev.isEmpty() || !"COMPLETED".equals(prev.getFirst()));
        if (newlyCompleted && o.contactId() != null) {
            events.record(org, o.contactId(), "PURCHASE_COMPLETED", at, source, "order:" + o.externalId(), Map.of("orderId", id.toString(),
                    "amountMinor", o.amountMinor(), "currency", o.currency()));
            jdbc.update("""
                    UPDATE contacts SET lifetime_value_minor = COALESCE(lifetime_value_minor, 0) + ?, lifetime_value_currency = COALESCE(lifetime_value_currency, ?)
                    WHERE id = ? AND (lifetime_value_currency IS NULL OR lifetime_value_currency = ?)""", o.amountMinor(), o.currency(), o.contactId(), o.currency());
            attribute(org, id, o.contactId(), o.amountMinor(), o.currency(), at);
        }
        if (("CANCELED".equals(o.status()) || "REFUNDED".equals(o.status()))) jdbc.update("DELETE FROM conversion_events WHERE order_id = ?", id);
        return id;
    }

    void attribute(UUID org, UUID orderId, UUID contactId, long amount, String currency, Instant at) {
        var touch = jdbc.queryForList("""
                SELECT m.id, m.origin_type, m.origin_id FROM messages m WHERE m.organization_id = ? AND m.contact_id = ? AND m.direction = 'OUTBOUND'
                  AND NOT m.is_test AND m.status IN ('SUBMITTED','SENT','DELIVERED','READ','REPLIED') AND m.created_at BETWEEN ? AND ?
                ORDER BY m.created_at DESC LIMIT 1""", org, contactId, Timestamp.from(at.minusSeconds(7 * 86400)), Timestamp.from(at));
        if (touch.isEmpty()) return;
        var t = touch.getFirst();
        UUID automationId = null, campaignId = null;
        if ("AUTOMATION".equals(t.get("origin_type"))) {
            automationId = jdbc.queryForList("SELECT automation_id FROM automation_instances WHERE id = ?", UUID.class, t.get("origin_id")).stream().findFirst().orElse(null);
        } else if ("CAMPAIGN".equals(t.get("origin_type"))) {
            campaignId = jdbc.queryForList("SELECT campaign_id FROM campaign_runs WHERE id = ?", UUID.class, t.get("origin_id")).stream().findFirst().orElse(null);
        }
        jdbc.update("""
                INSERT INTO conversion_events (id, organization_id, order_id, contact_id, message_id, automation_id, campaign_id, amount_minor, currency, occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (order_id) DO NOTHING""", Ids.newId(), org, orderId, contactId, t.get("id"), automationId, campaignId,
                amount, currency, Timestamp.from(at));
    }

    public record AppointmentInput(String externalId, UUID contactId, Instant startsAt, Instant endsAt, String status, String title, String location) {}

    @Transactional
    public UUID upsertAppointment(UUID org, AppointmentInput a, String source) {
        if (a.contactId() == null || a.startsAt() == null) throw ApiException.badRequest("contactId and startsAt required");
        if (!Set.of("SCHEDULED", "CONFIRMED", "CANCELED", "COMPLETED", "NO_SHOW").contains(a.status())) throw ApiException.badRequest("Invalid status");
        Integer n = jdbc.queryForObject("SELECT count(*) FROM contacts WHERE id = ? AND organization_id = ?", Integer.class, a.contactId(), org);
        if (n == null || n == 0) throw ApiException.notFound("Contact");
        var prev = a.externalId() == null ? java.util.List.<String>of()
                : jdbc.queryForList("SELECT status FROM appointments WHERE organization_id = ? AND external_id = ?", String.class, org, a.externalId());
        UUID id = Ids.newId();
        if (prev.isEmpty()) {
            jdbc.update("""
                    INSERT INTO appointments (id, organization_id, contact_id, external_id, starts_at, ends_at, status, title, location, source)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""", id, org, a.contactId(), a.externalId(), Timestamp.from(a.startsAt()),
                    a.endsAt() == null ? null : Timestamp.from(a.endsAt()), a.status(), a.title(), a.location(), source);
            events.record(org, a.contactId(), "APPOINTMENT_CREATED", Instant.now(), source, "appt-created:" + id, Map.of("appointmentId", id.toString(),
                    "startsAt", a.startsAt().toString()));
        } else {
            id = jdbc.queryForObject("""
                    UPDATE appointments SET starts_at = ?, ends_at = ?, status = ?, title = ?, location = ?, updated_at = now()
                    WHERE organization_id = ? AND external_id = ? RETURNING id""", UUID.class, Timestamp.from(a.startsAt()),
                    a.endsAt() == null ? null : Timestamp.from(a.endsAt()), a.status(), a.title(), a.location(), org, a.externalId());
            if ("CANCELED".equals(a.status()) && !"CANCELED".equals(prev.getFirst())) {
                events.record(org, a.contactId(), "APPOINTMENT_CANCELED", Instant.now(), source, "appt-canceled:" + id, Map.of("appointmentId", id.toString()));
            }
            if ("COMPLETED".equals(a.status()) && !"COMPLETED".equals(prev.getFirst())) {
                events.record(org, a.contactId(), "SERVICE_COMPLETED", Instant.now(), source, "appt-done:" + id, Map.of("appointmentId", id.toString()));
            }
        }
        jdbc.update("""
                UPDATE contacts SET next_appointment_at = (SELECT min(starts_at) FROM appointments WHERE contact_id = ? AND status IN ('SCHEDULED','CONFIRMED') AND starts_at > now())
                WHERE id = ?""", a.contactId(), a.contactId());
        return id;
    }
}
