package com.platform.telephony;

import com.platform.contacts.ContactDtos.ContactInput;
import com.platform.contacts.ContactEventService;
import com.platform.contacts.ContactRepository;
import com.platform.contacts.ContactService;
import com.platform.shared.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Call events from the provider (Twilio) or an authenticated PBX integration. A call is "missed"
 * when the provider reports no-answer/busy/failed/canceled for an inbound call — never inferred
 * from a number the user typed. A missed call creates (once) a callback task, an opportunity and a
 * MISSED_CALL contact event that automations can react to (e.g. a WhatsApp message if consent allows).
 */
@Service
public class CallService {
    static final Set<String> MISSED = Set.of("NO_ANSWER", "BUSY", "FAILED", "CANCELED", "MISSED");
    private final JdbcTemplate jdbc;
    private final ContactRepository contacts;
    private final ContactService contactService;
    private final ContactEventService events;

    public CallService(JdbcTemplate jdbc, ContactRepository contacts, ContactService contactService, ContactEventService events) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.contactService = contactService;
        this.events = events;
    }

    public record CallEvent(UUID orgId, UUID phoneNumberId, String provider, String providerCallId, String direction, String from, String to,
                            String status, Integer durationSeconds, Instant startedAt, Instant endedAt) {}

    @Transactional
    public UUID record(CallEvent e) {
        UUID contactId = null;
        String counterpart = "INBOUND".equals(e.direction()) ? e.from() : e.to();
        if (counterpart != null && counterpart.startsWith("+")) {
            contactId = contacts.findByPhone(e.orgId(), counterpart).map(c -> c.id()).orElseGet(() -> "INBOUND".equals(e.direction())
                    ? contactService.upsert(e.orgId(), new ContactInput(null, null, null, counterpart, null, null, null, null, null, null, null, null, null,
                            null, List.of("caller"), null), "INBOUND", "call").getKey().id() : null);
        }
        boolean answered = "COMPLETED".equals(e.status()) && e.durationSeconds() != null && e.durationSeconds() > 0;
        boolean isTest = false;
        if (e.phoneNumberId() != null) {
            // First observed real inbound call proves the routing works → monitoring enabled.
            isTest = jdbc.update("""
                    UPDATE phone_numbers SET status = 'MONITORING', test_call_passed_at = now(), updated_at = now()
                    WHERE id = ? AND status = 'TEST_PENDING'""", e.phoneNumberId()) == 1;
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO call_events (id, organization_id, phone_number_id, contact_id, provider, provider_call_id, direction, from_e164, to_e164, status,
                  answered, duration_seconds, started_at, ended_at, is_test)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (provider, provider_call_id) DO UPDATE SET status = EXCLUDED.status, answered = EXCLUDED.answered,
                  duration_seconds = COALESCE(EXCLUDED.duration_seconds, call_events.duration_seconds), ended_at = COALESCE(EXCLUDED.ended_at, call_events.ended_at),
                  updated_at = now()
                RETURNING id""", UUID.class, Ids.newId(), e.orgId(), e.phoneNumberId(), contactId, e.provider(), e.providerCallId(), e.direction(),
                e.from(), e.to(), e.status(), answered, e.durationSeconds(), ts(e.startedAt()), ts(e.endedAt()), isTest);
        if (answered && contactId != null) {
            events.record(e.orgId(), contactId, "CALL_ANSWERED", e.endedAt() == null ? Instant.now() : e.endedAt(), "TELEPHONY", "call-ans:" + e.providerCallId(), Map.of());
        }
        if ("INBOUND".equals(e.direction()) && MISSED.contains(e.status())) missed(e, id, contactId);
        return id;
    }

    private void missed(CallEvent e, UUID callId, UUID contactId) {
        Instant at = e.startedAt() == null ? Instant.now() : e.startedAt();
        UUID missedId = Ids.newId();
        int n = jdbc.update("INSERT INTO missed_calls (id, organization_id, call_event_id, contact_id, from_e164, occurred_at) VALUES (?,?,?,?,?,?) ON CONFLICT (call_event_id) DO NOTHING",
                missedId, e.orgId(), callId, contactId, e.from(), Timestamp.from(at));
        if (n == 0) return; // already processed (duplicate status callback)
        // One open callback task per caller: repeated missed calls attach to the existing open task.
        var open = jdbc.queryForList("SELECT id FROM callback_tasks WHERE organization_id = ? AND phone_e164 = ? AND status IN ('OPEN','IN_PROGRESS') LIMIT 1",
                UUID.class, e.orgId(), e.from());
        UUID taskId = open.isEmpty() ? Ids.newId() : open.getFirst();
        if (open.isEmpty()) {
            jdbc.update("""
                    INSERT INTO callback_tasks (id, organization_id, contact_id, phone_e164, source_type, source_id, status, due_at, notes)
                    VALUES (?,?,?,?, 'MISSED_CALL', ?, 'OPEN', ?, 'Missed call — call back')""",
                    taskId, e.orgId(), contactId, e.from(), missedId, Timestamp.from(at.plusSeconds(3600)));
            jdbc.update("""
                    INSERT INTO opportunities (id, organization_id, contact_id, title, status, source_type, source_id)
                    VALUES (?,?,?, 'Missed call from ' || ?, 'OPEN', 'MISSED_CALL', ?)""", Ids.newId(), e.orgId(), contactId, e.from(), missedId);
        }
        jdbc.update("UPDATE missed_calls SET callback_task_id = ? WHERE id = ?", taskId, missedId);
        if (contactId != null) events.record(e.orgId(), contactId, "MISSED_CALL", at, "TELEPHONY", "missed:" + e.providerCallId(), Map.of("from", e.from()));
    }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
}
