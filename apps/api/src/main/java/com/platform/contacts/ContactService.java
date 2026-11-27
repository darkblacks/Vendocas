package com.platform.contacts;

import com.platform.audit.AuditService;
import com.platform.contacts.ContactDtos.*;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.outbox.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

@Service
public class ContactService {
    private final ContactRepository repo;
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;
    private final AuditService audit;

    public ContactService(ContactRepository repo, JdbcTemplate jdbc, OutboxService outbox, AuditService audit) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
    }

    public Contact get(UUID orgId, UUID id) { return repo.find(orgId, id).orElseThrow(() -> ApiException.notFound("Contact")); }

    @Transactional
    public Contact create(UUID orgId, ContactInput in, String source, UUID actorId) {
        Contact c = build(orgId, Ids.newId(), in, source, null);
        if (c.email() == null && c.phoneE164() == null) throw ApiException.badRequest("A contact needs an email or a phone number");
        // Only look up the identifiers the contact has: PostgreSQL rejects the NUL character the old placeholder used.
        Optional.<Contact>empty()
                .or(() -> c.email() == null ? Optional.empty() : repo.findByEmail(orgId, c.email()))
                .or(() -> c.phoneE164() == null ? Optional.empty() : repo.findByPhone(orgId, c.phoneE164()))
                .ifPresent(existing -> { throw new ApiException(ErrorCode.CONFLICT, "A contact with this email or phone already exists",
                        Map.of("existingContactId", existing.id())); });
        repo.insert(c);
        if (in.tags() != null) repo.setTags(orgId, c.id(), in.tags());
        outbox.publish(orgId, "contact", c.id(), "contact.created", Map.of("contactId", c.id().toString(), "source", source));
        if (actorId != null) audit.user(orgId, actorId, "contact.created", "contact", c.id(), Map.of("source", source));
        return get(orgId, c.id());
    }

    @Transactional
    public Contact update(UUID orgId, UUID id, ContactInput in, UUID actorId) {
        Contact current = get(orgId, id);
        if ("ERASED".equals(current.status())) throw ApiException.invalidState("Contact was erased");
        Contact c = build(orgId, id, in, current.source(), current);
        repo.update(c);
        if (in.tags() != null) repo.setTags(orgId, id, in.tags());
        outbox.publish(orgId, "contact", id, "contact.updated", Map.of("contactId", id.toString()));
        if (actorId != null) audit.user(orgId, actorId, "contact.updated", "contact", id, Map.of());
        return get(orgId, id);
    }

    /** Upsert by external id → email → phone. Used by import, API and inbound integrations. Returns (contact, created). */
    @Transactional
    public Map.Entry<Contact, Boolean> upsert(UUID orgId, ContactInput in, String source, String sourceRef) {
        String region = orgCountry(orgId);
        String email = normalizeEmail(in.email());
        String phone = in.phone() == null ? null : Phones.toE164(in.phone(), in.countryCode() != null ? in.countryCode() : region).orElse(null);
        Optional<Contact> existing = Optional.<Contact>empty()
                .or(() -> in.externalId() == null ? Optional.empty() : repo.findByExternalId(orgId, in.externalId()))
                .or(() -> email == null ? Optional.empty() : repo.findByEmail(orgId, email))
                .or(() -> phone == null ? Optional.empty() : repo.findByPhone(orgId, phone));
        if (existing.isPresent()) {
            Contact merged = merge(existing.get(), build(orgId, existing.get().id(), in, source, existing.get()));
            repo.update(merged);
            if (in.tags() != null && !in.tags().isEmpty()) {
                Set<String> all = new LinkedHashSet<>(existing.get().tags());
                all.addAll(in.tags());
                repo.setTags(orgId, merged.id(), all);
            }
            outbox.publish(orgId, "contact", merged.id(), "contact.updated", Map.of("contactId", merged.id().toString()));
            return Map.entry(merged, false);
        }
        Contact c = build(orgId, Ids.newId(), in, source, null);
        if (c.email() == null && c.phoneE164() == null) throw ApiException.badRequest("Email or phone required");
        c = withSourceRef(c, sourceRef);
        repo.insert(c);
        if (sourceRef != null) jdbc.update("UPDATE contacts SET source_ref = ? WHERE id = ?", sourceRef, c.id());
        if (in.tags() != null) repo.setTags(orgId, c.id(), in.tags());
        outbox.publish(orgId, "contact", c.id(), "contact.created", Map.of("contactId", c.id().toString(), "source", source));
        return Map.entry(c, true);
    }

    /** GDPR/LGPD erasure: PII removed, addresses kept only in the suppression list to honour opt-outs. */
    @Transactional
    public void erase(UUID orgId, UUID id, UUID actorId) {
        Contact c = get(orgId, id);
        if (c.email() != null) suppress(orgId, "EMAIL", c.email());
        if (c.phoneE164() != null) for (String ch : List.of("SMS", "WHATSAPP", "VOICE")) suppress(orgId, ch, c.phoneE164());
        jdbc.update("""
                UPDATE contacts SET name = NULL, email = NULL, phone_e164 = NULL, external_id = NULL, birth_date = NULL,
                  custom_fields = '{}'::jsonb, status = 'ERASED', updated_at = now() WHERE organization_id = ? AND id = ?""", orgId, id);
        jdbc.update("DELETE FROM contact_identifiers WHERE contact_id = ?", id);
        jdbc.update("UPDATE messages SET body = NULL, subject = NULL, to_address = NULL WHERE organization_id = ? AND contact_id = ?", orgId, id);
        jdbc.update("UPDATE automation_instances SET state='CANCELED', result='Contact erased', completed_at=now() WHERE contact_id = ? AND state IN ('PENDING','WAITING','READY')", id);
        audit.user(orgId, actorId, "contact.erased", "contact", id, Map.of());
    }

    private void suppress(UUID orgId, String channel, String address) {
        jdbc.update("""
                INSERT INTO suppression_list (id, organization_id, channel, address, reason, scope) VALUES (?,?,?,?,'ERASURE','ALL')
                ON CONFLICT (organization_id, channel, address) DO UPDATE SET scope = 'ALL'""", Ids.newId(), orgId, channel, address);
    }

    public List<CustomFieldView> customFields(UUID orgId) {
        return jdbc.query("SELECT key, label, data_type, is_sensitive FROM contact_custom_fields WHERE organization_id = ? ORDER BY key",
                (rs, i) -> new CustomFieldView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), orgId);
    }

    @Transactional
    public CustomFieldView upsertCustomField(UUID orgId, CustomFieldInput in) {
        jdbc.update("""
                INSERT INTO contact_custom_fields (id, organization_id, key, label, data_type, is_sensitive) VALUES (?,?,?,?,?,?)
                ON CONFLICT (organization_id, key) DO UPDATE SET label = EXCLUDED.label, is_sensitive = EXCLUDED.is_sensitive""",
                Ids.newId(), orgId, in.key(), in.label(), in.dataType(), in.sensitive());
        return new CustomFieldView(in.key(), in.label(), in.dataType(), in.sensitive());
    }

    public List<TimelineItem> timeline(UUID orgId, UUID contactId) {
        get(orgId, contactId);
        return jdbc.query("""
                (SELECT 'EVENT' AS kind, type, NULL AS channel, NULL AS status, source AS summary, occurred_at FROM contact_events
                   WHERE organization_id = ? AND contact_id = ?)
                UNION ALL
                (SELECT 'MESSAGE', direction, channel, status, left(coalesce(subject, body), 140), created_at FROM messages
                   WHERE organization_id = ? AND contact_id = ?)
                UNION ALL
                (SELECT 'CONSENT', e.action, c.channel, c.purpose, e.source, e.occurred_at FROM consent_evidence e
                   JOIN contact_consents c ON c.id = e.consent_id WHERE c.organization_id = ? AND c.contact_id = ?)
                ORDER BY occurred_at DESC LIMIT 200""",
                (rs, i) -> new TimelineItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant()), orgId, contactId, orgId, contactId, orgId, contactId);
    }

    // ---- mapping helpers ----
    private Contact build(UUID orgId, UUID id, ContactInput in, String source, Contact current) {
        String region = orgCountry(orgId);
        String email = normalizeEmail(in.email());
        String phone = null;
        if (in.phone() != null && !in.phone().isBlank()) {
            phone = Phones.toE164(in.phone(), in.countryCode() != null ? in.countryCode() : region)
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid phone number", Map.of("fields", Map.of("phone", "invalid"))));
        }
        if (in.timeZone() != null) {
            try { ZoneId.of(in.timeZone()); } catch (Exception e) { throw ApiException.badRequest("Invalid time zone"); }
        }
        validateCustomFields(orgId, in.customFields());
        String country = in.countryCode() != null ? in.countryCode() : Phones.region(phone).orElse(current == null ? null : current.countryCode());
        Instant now = Instant.now();
        return new Contact(id, orgId, in.externalId(), trim(in.name()), email, phone, in.locale(), country, in.timeZone(), in.birthDate(),
                in.lastInteractionAt(), in.lastPurchaseAt(), in.lastVisitAt(), in.nextAppointmentAt(), in.lifetimeValueMinor(),
                in.lifetimeValueCurrency(), current == null ? "ACTIVE" : current.status(), source,
                in.customFields() == null ? (current == null ? Map.of() : current.customFields()) : in.customFields(), List.of(),
                current == null ? now : current.createdAt(), now);
    }

    private static Contact merge(Contact old, Contact in) {
        return new Contact(old.id(), old.organizationId(), coalesce(in.externalId(), old.externalId()), coalesce(in.name(), old.name()),
                coalesce(in.email(), old.email()), coalesce(in.phoneE164(), old.phoneE164()), coalesce(in.locale(), old.locale()),
                coalesce(in.countryCode(), old.countryCode()), coalesce(in.timeZone(), old.timeZone()), coalesce(in.birthDate(), old.birthDate()),
                max(in.lastInteractionAt(), old.lastInteractionAt()), max(in.lastPurchaseAt(), old.lastPurchaseAt()),
                max(in.lastVisitAt(), old.lastVisitAt()), coalesce(in.nextAppointmentAt(), old.nextAppointmentAt()),
                coalesce(in.lifetimeValueMinor(), old.lifetimeValueMinor()), coalesce(in.lifetimeValueCurrency(), old.lifetimeValueCurrency()),
                old.status(), old.source(), mergeMaps(old.customFields(), in.customFields()), old.tags(), old.createdAt(), Instant.now());
    }

    private static Contact withSourceRef(Contact c, String ref) { return c; }

    private void validateCustomFields(UUID orgId, Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        Set<String> keys = new HashSet<>(jdbc.queryForList("SELECT key FROM contact_custom_fields WHERE organization_id = ?", String.class, orgId));
        for (String k : values.keySet()) if (!keys.contains(k)) throw ApiException.badRequest("Unknown custom field: " + k);
    }

    private String orgCountry(UUID orgId) {
        return jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, orgId);
    }

    static String normalizeEmail(String e) { return e == null || e.isBlank() ? null : e.trim().toLowerCase(); }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static <T> T coalesce(T a, T b) { return a != null ? a : b; }

    private static Instant max(Instant a, Instant b) { return a == null ? b : b == null ? a : a.isAfter(b) ? a : b; }

    private static Map<String, Object> mergeMaps(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> m = new HashMap<>(a == null ? Map.of() : a);
        if (b != null) m.putAll(b);
        return m;
    }
}
