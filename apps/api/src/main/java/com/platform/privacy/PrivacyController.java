package com.platform.privacy;

import com.platform.audit.AuditService;
import com.platform.contacts.ContactService;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data-subject requests handled by the organization (controller) with our tooling (processor):
 * access/export (machine-readable JSON), rectification (normal edit), erasure (anonymization +
 * suppression), objection (marketing opt-out). Due date defaults to 30 days (GDPR art. 12(3);
 * LGPD art. 19 allows 15 days for complete replies — the UI shows the due date).
 */
@RestController
@RequestMapping("/api/v1/privacy")
@Tag(name = "privacy")
public class PrivacyController {
    private final JdbcTemplate jdbc;
    private final ContactService contacts;
    private final AuditService audit;

    public PrivacyController(JdbcTemplate jdbc, ContactService contacts, AuditService audit) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.audit = audit;
    }

    public record RequestInput(UUID contactId, @Email String requesterEmail,
                               @NotBlank @Pattern(regexp = "ACCESS|EXPORT|RECTIFICATION|ERASURE|RESTRICTION|OBJECTION") String type,
                               String notes, Integer dueInDays) {}

    public record RequestView(UUID id, UUID contactId, String type, String status, String requesterEmail, Instant dueAt, Instant completedAt, Instant createdAt) {}

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.PRIVACY_MANAGE)
    public Map<String, UUID> create(@Valid @RequestBody RequestInput in) {
        var t = TenantContext.current();
        if (in.contactId() != null) contacts.get(t.orgId(), in.contactId());
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO privacy_requests (id, organization_id, contact_id, type, status, requester_email, due_at, notes) VALUES (?,?,?,?, 'RECEIVED', ?, ?, ?)",
                id, t.orgId(), in.contactId(), in.type(), in.requesterEmail(),
                Timestamp.from(Instant.now().plus(in.dueInDays() == null ? 30 : Math.min(in.dueInDays(), 30), ChronoUnit.DAYS)), in.notes());
        audit.user(t.orgId(), t.userId(), "privacy.request_created", "privacy_request", id, Map.of("type", in.type()));
        return Map.of("id", id);
    }

    @GetMapping("/requests")
    @RequiresPermission(Permission.PRIVACY_MANAGE)
    public List<RequestView> list() {
        return jdbc.query("SELECT id, contact_id, type, status, requester_email, due_at, completed_at, created_at FROM privacy_requests WHERE organization_id = ? ORDER BY created_at DESC",
                (rs, i) -> new RequestView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()),
                TenantContext.current().orgId());
    }

    /** Executes the request: ERASURE anonymizes; OBJECTION withdraws marketing consent; ACCESS/EXPORT returns the data. */
    @PostMapping("/requests/{id}/execute")
    @RequiresPermission(Permission.PRIVACY_MANAGE)
    @Transactional
    public Map<String, Object> execute(@PathVariable UUID id) {
        var t = TenantContext.current();
        var rows = jdbc.queryForList("SELECT * FROM privacy_requests WHERE id = ? AND organization_id = ?", id, t.orgId());
        if (rows.isEmpty()) throw ApiException.notFound("Request");
        var r = rows.getFirst();
        UUID contactId = (UUID) r.get("contact_id");
        if (contactId == null) throw ApiException.badRequest("Link the request to a contact first");
        Map<String, Object> result = new LinkedHashMap<>();
        switch ((String) r.get("type")) {
            case "ACCESS", "EXPORT" -> result.put("data", export(t.orgId(), contactId));
            case "ERASURE" -> contacts.erase(t.orgId(), contactId, t.userId());
            case "OBJECTION", "RESTRICTION" -> jdbc.update("""
                    UPDATE contact_consents SET status = 'WITHDRAWN', withdrawn_at = now(), updated_at = now()
                    WHERE contact_id = ? AND organization_id = ? AND purpose IN ('MARKETING','REACTIVATION')""", contactId, t.orgId());
            default -> { }
        }
        jdbc.update("UPDATE privacy_requests SET status = 'COMPLETED', completed_at = now(), handled_by = ? WHERE id = ?", t.userId(), id);
        audit.user(t.orgId(), t.userId(), "privacy.request_executed", "privacy_request", id, Map.of("type", r.get("type")));
        result.put("status", "COMPLETED");
        return result;
    }

    @GetMapping("/contacts/{contactId}/export")
    @RequiresPermission(Permission.CONTACTS_EXPORT)
    public Map<String, Object> exportContact(@PathVariable UUID contactId) {
        var t = TenantContext.current();
        audit.user(t.orgId(), t.userId(), "privacy.contact_exported", "contact", contactId, Map.of());
        return export(t.orgId(), contactId);
    }

    Map<String, Object> export(UUID org, UUID contactId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contact", contacts.get(org, contactId));
        out.put("consents", jdbc.queryForList("SELECT channel, purpose, status, legal_basis, granted_at, withdrawn_at FROM contact_consents WHERE contact_id = ? AND organization_id = ?", contactId, org));
        out.put("consentEvidence", jdbc.queryForList("""
                SELECT e.action, e.source, e.wording, e.form_url, e.occurred_at FROM consent_evidence e JOIN contact_consents c ON c.id = e.consent_id
                WHERE c.contact_id = ? AND c.organization_id = ?""", contactId, org));
        out.put("messages", jdbc.queryForList("SELECT direction, channel, purpose, status, subject, body, created_at FROM messages WHERE contact_id = ? AND organization_id = ? ORDER BY created_at", contactId, org));
        out.put("events", jdbc.queryForList("SELECT type, occurred_at, source, properties::text FROM contact_events WHERE contact_id = ? AND organization_id = ? ORDER BY occurred_at", contactId, org));
        out.put("orders", jdbc.queryForList("SELECT external_id, status, amount_minor, currency, occurred_at FROM orders WHERE contact_id = ? AND organization_id = ?", contactId, org));
        out.put("appointments", jdbc.queryForList("SELECT starts_at, status, title FROM appointments WHERE contact_id = ? AND organization_id = ?", contactId, org));
        out.put("exportedAt", Instant.now().toString());
        return out;
    }
}
