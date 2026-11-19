package com.platform.automation.engine;

import com.platform.automation.model.AutomationDefinition;
import com.platform.automation.model.TriggerType;
import com.platform.contacts.Contact;
import com.platform.contacts.ContactRepository;
import com.platform.shared.outbox.OutboxEvent;
import com.platform.shared.outbox.OutboxHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Consumes contact events from the outbox: (1) cancels waiting instances whose automation lists the
 * event as an exit condition (e.g. purchase completed during the wait), then (2) starts instances
 * of ACTIVE automations whose trigger matches.
 */
@Component
public class TriggerMatcher implements OutboxHandler {
    private final JdbcTemplate jdbc;
    private final VersionCache versions;
    private final InstanceFactory instances;
    private final ContactRepository contacts;
    private final Clock clock;

    public TriggerMatcher(JdbcTemplate jdbc, VersionCache versions, InstanceFactory instances, ContactRepository contacts, Clock clock) {
        this.jdbc = jdbc;
        this.versions = versions;
        this.instances = instances;
        this.contacts = contacts;
        this.clock = clock;
    }

    @Override public String consumer() { return "automation-trigger-matcher"; }

    @Override public Set<String> eventTypes() { return Set.of("contact.event", "contact.created", "contact.updated"); }

    @Override
    public void handle(OutboxEvent e) {
        UUID org = e.organizationId();
        String contactIdStr = e.payload().path("contactId").asString(null);
        if (org == null || contactIdStr == null) return;
        UUID contactId = UUID.fromString(contactIdStr);
        String type = "contact.event".equals(e.eventType()) ? e.payload().path("type").asString() : e.eventType();
        Instant occurredAt = e.payload().has("occurredAt") ? Instant.parse(e.payload().path("occurredAt").asString()) : clock.instant();

        cancelOnExit(org, contactId, type);

        Optional<Contact> contact = contacts.find(org, contactId);
        if (contact.isEmpty() || !"ACTIVE".equals(contact.get().status())) return;
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT d.id, d.active_version_id, t.trigger_type, t.event_type FROM automation_definitions d
                JOIN automation_triggers t ON t.version_id = d.active_version_id
                WHERE d.organization_id = ? AND d.status = 'ACTIVE'""", org);
        for (var a : active) {
            TriggerType tt = TriggerType.valueOf((String) a.get("trigger_type"));
            boolean matches = tt.kind() == TriggerType.Kind.EVENT &&
                    (tt == TriggerType.EXTERNAL_EVENT ? type.equals(a.get("event_type")) : type.equals(tt.eventType()));
            if (!matches) continue;
            UUID versionId = (UUID) a.get("active_version_id");
            AutomationDefinition def = versions.get(versionId);
            if (!InstanceFactory.matchesAudience(def.trigger().audience(), contact.get().tags(), contact.get().countryCode())) continue;
            String eventId = e.payload().path("eventId").asString(e.id().toString());
            String key = switch (def.trigger().reentry() == null ? "ONCE_PER_EVENT" : def.trigger().reentry()) {
                case "ONCE" -> "ONCE:" + contactId;
                case "ONCE_PER_DAY" -> "DAY:" + contactId + ":" + LocalDate.ofInstant(occurredAt, ZoneOffset.UTC);
                default -> "EVT:" + eventId;
            };
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("triggerAt", occurredAt.toString());
            ctx.put("triggerType", type);
            e.payload().properties().forEach(p -> { if (p.getValue().isValueNode()) ctx.put("event." + p.getKey(), p.getValue().asString()); });
            instances.create(org, (UUID) a.get("id"), versionId, def, contactId, type,
                    e.payload().has("eventId") ? UUID.fromString(e.payload().path("eventId").asString()) : null, key, ctx, clock.instant());
        }
    }

    /** Exit conditions: instances still waiting for this contact are canceled when the event happens. */
    void cancelOnExit(UUID org, UUID contactId, String type) {
        List<Map<String, Object>> waiting = jdbc.queryForList("""
                SELECT i.id, i.version_id FROM automation_instances i
                WHERE i.organization_id = ? AND i.contact_id = ? AND i.state IN ('PENDING','WAITING','READY')""", org, contactId);
        for (var w : waiting) {
            AutomationDefinition def = versions.get((UUID) w.get("version_id"));
            if (def.settings() != null && def.settings().exits().contains(type)) {
                jdbc.update("""
                        UPDATE automation_instances SET state = 'CANCELED', result = ?, completed_at = now(), updated_at = now()
                        WHERE id = ? AND state IN ('PENDING','WAITING','READY')""", "Exit event: " + type, w.get("id"));
            }
        }
    }
}
