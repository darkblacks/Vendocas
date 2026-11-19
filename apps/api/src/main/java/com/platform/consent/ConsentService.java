package com.platform.consent;

import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Consent per contact × channel × purpose with an append-only evidence trail. */
@Service
public class ConsentService {
    public static final Set<String> CHANNELS = Set.of("EMAIL", "WHATSAPP", "SMS", "VOICE");
    public static final Set<String> PURPOSES = Set.of("TRANSACTIONAL", "SERVICE_REMINDER", "CUSTOMER_CARE", "MARKETING", "REACTIVATION");
    public static final Set<String> BASES = Set.of("CONSENT", "CONTRACT", "LEGITIMATE_INTEREST", "EXISTING_CUSTOMER_SOFT_OPT_IN", "LEGAL_OBLIGATION");
    private final JdbcTemplate jdbc;

    public ConsentService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Evidence(String source, String capturedBy, String ipAddress, String userAgent, String wording, String formUrl, Instant occurredAt) {}

    public record ConsentView(String channel, String purpose, String status, String legalBasis, Instant grantedAt, Instant withdrawnAt) {}

    @Transactional
    public void set(UUID orgId, UUID contactId, String channel, String purpose, String status, String legalBasis, Evidence ev) {
        if (!CHANNELS.contains(channel) || !PURPOSES.contains(purpose)) throw ApiException.badRequest("Invalid channel or purpose");
        if (!Set.of("GRANTED", "WITHDRAWN", "DENIED").contains(status)) throw ApiException.badRequest("Invalid status");
        if ("GRANTED".equals(status) && !BASES.contains(legalBasis)) throw ApiException.badRequest("A valid legal basis is required");
        if ("GRANTED".equals(status) && "IMPORT_DECLARED".equals(ev.source()) && (ev.wording() == null || ev.wording().isBlank())) {
            throw ApiException.badRequest("Describe how consent was collected (evidence) when declaring it for imported contacts");
        }
        Integer owned = jdbc.queryForObject("SELECT count(*) FROM contacts WHERE id = ? AND organization_id = ?", Integer.class, contactId, orgId);
        if (owned == null || owned == 0) throw ApiException.notFound("Contact");
        Instant at = ev.occurredAt() == null ? Instant.now() : ev.occurredAt();
        UUID consentId = jdbc.queryForObject("""
                INSERT INTO contact_consents (id, organization_id, contact_id, channel, purpose, status, legal_basis, granted_at, withdrawn_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (contact_id, channel, purpose) DO UPDATE SET status = EXCLUDED.status,
                    legal_basis = CASE WHEN EXCLUDED.status = 'GRANTED' THEN EXCLUDED.legal_basis ELSE contact_consents.legal_basis END,
                    granted_at = CASE WHEN EXCLUDED.status = 'GRANTED' THEN EXCLUDED.granted_at ELSE contact_consents.granted_at END,
                    withdrawn_at = CASE WHEN EXCLUDED.status <> 'GRANTED' THEN EXCLUDED.withdrawn_at ELSE NULL END, updated_at = now()
                RETURNING id""", UUID.class, Ids.newId(), orgId, contactId, channel, purpose, status,
                legalBasis == null ? "CONSENT" : legalBasis, "GRANTED".equals(status) ? Timestamp.from(at) : null,
                "GRANTED".equals(status) ? null : Timestamp.from(at));
        jdbc.update("""
                INSERT INTO consent_evidence (id, organization_id, consent_id, action, source, captured_by, ip_address, user_agent, wording, form_url, occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""", Ids.newId(), orgId, consentId, status, ev.source(), ev.capturedBy(), ev.ipAddress(),
                truncate(ev.userAgent(), 300), ev.wording(), ev.formUrl(), Timestamp.from(at));
    }

    /** Opt-out from promotional communications on a channel (unsubscribe link, STOP keyword). */
    @Transactional
    public void optOutMarketing(UUID orgId, UUID contactId, String channel, String address, String source) {
        for (String purpose : List.of("MARKETING", "REACTIVATION")) {
            set(orgId, contactId, channel, purpose, "WITHDRAWN", null, new Evidence(source, "consumer", null, null, null, null, Instant.now()));
        }
        if (address != null) {
            jdbc.update("""
                    INSERT INTO suppression_list (id, organization_id, channel, address, reason, scope) VALUES (?,?,?,?,?, 'MARKETING')
                    ON CONFLICT (organization_id, channel, address) DO NOTHING""", Ids.newId(), orgId, channel, address,
                    "INBOUND_STOP".equals(source) ? "STOP_KEYWORD" : "UNSUBSCRIBED");
        }
    }

    public List<ConsentView> list(UUID orgId, UUID contactId) {
        return jdbc.query("""
                SELECT channel, purpose, status, legal_basis, granted_at, withdrawn_at FROM contact_consents
                WHERE organization_id = ? AND contact_id = ? ORDER BY channel, purpose""",
                (rs, i) -> new ConsentView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()), orgId, contactId);
    }

    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n); }
}
