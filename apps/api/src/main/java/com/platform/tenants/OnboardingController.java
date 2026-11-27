package com.platform.tenants;

import com.platform.audit.AuditService;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Mandatory onboarding (cannot be skipped): 1) the store — name, type of establishment, sales model (B2C: customers
 * come, buy and leave — automatic board from the PDV; B2B: sells to companies — negotiation funnel; pre-set per type
 * of establishment and editable) and the e-mail (pre-filled with the login) and phone shown under the store name in every alert;
 * 2) WhatsApp — connect the store's own WhatsApp Business or ask the Vendocas to provide a number;
 * 3) e-mail — the store's own address or an address on the Vendocas domain. The app stays on the onboarding until
 * {@code POST /onboarding/complete} succeeds. Connecting the channels themselves uses the regular channel endpoints.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@Tag(name = "onboarding")
public class OnboardingController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public OnboardingController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record State(boolean completed, Instant completedAt, String storeName, String businessSegment, String alertContact,
                        String whatsappChoice, String emailChoice, String emailAddress, String whatsappChannelStatus, String emailChannelStatus,
                        String salesModel, String alertEmail) {}

    public record Update(@Size(min = 1, max = 250) String storeName, @Pattern(regexp = "^[a-z0-9_]{2,80}$") String businessSegment,
                         @Pattern(regexp = "B2C|B2B") String salesModel, @Size(max = 200) String alertContact,
                         @Email @Size(max = 254) String alertEmail,
                         @Pattern(regexp = "CONNECT_OWN|PROVISION_NUMBER") String whatsappChoice,
                         @Pattern(regexp = "OWN_ADDRESS|VENDOCAS_ADDRESS") String emailChoice, @Email @Size(max = 254) String emailAddress) {}

    private void ensureRow(UUID org) {
        jdbc.update("INSERT INTO organization_onboarding (organization_id) VALUES (?) ON CONFLICT DO NOTHING", org);
    }

    @GetMapping
    @RequiresPermission(Permission.ORG_READ)
    public State get() {
        UUID org = TenantContext.current().orgId();
        ensureRow(org);
        return jdbc.queryForObject("""
                SELECT ob.completed_at, o.trade_name, ob.business_segment, s.alert_contact,
                       ob.whatsapp_choice, ob.email_choice, ob.email_address,
                       (SELECT status FROM channel_connections WHERE organization_id = o.id AND channel = 'WHATSAPP' ORDER BY updated_at DESC LIMIT 1) AS wa,
                       (SELECT status FROM channel_connections WHERE organization_id = o.id AND channel = 'EMAIL' ORDER BY updated_at DESC LIMIT 1) AS em,
                       ob.sales_model,
                       -- the footer e-mail starts as the e-mail of whoever is doing the onboarding (their login)
                       COALESCE(s.alert_email, (SELECT email::text FROM users WHERE id = ?), o.contact_email::text) AS alert_email
                FROM organization_onboarding ob JOIN organizations o ON o.id = ob.organization_id
                LEFT JOIN organization_settings s ON s.organization_id = o.id WHERE ob.organization_id = ?""",
                (rs, i) -> new State(rs.getTimestamp(1) != null, rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)), userId(), org);
    }

    /** Saves any step (partial). */
    @PutMapping
    @RequiresPermission(Permission.ORG_MANAGE)
    @Transactional
    public State save(@Valid @RequestBody Update u) {
        var t = TenantContext.current();
        ensureRow(t.orgId());
        if (u.storeName() != null) jdbc.update("UPDATE organizations SET trade_name = ?, updated_at = now() WHERE id = ?", u.storeName().trim(), t.orgId());
        if (u.alertEmail() != null) {
            jdbc.update("UPDATE organization_settings SET alert_email = ? WHERE organization_id = ?", u.alertEmail().isBlank() ? null : u.alertEmail().trim(), t.orgId());
        }
        if (u.alertContact() != null) {
            jdbc.update("UPDATE organization_settings SET alert_contact = ? WHERE organization_id = ?", u.alertContact().isBlank() ? null : u.alertContact().trim(), t.orgId());
        }
        jdbc.update("""
                UPDATE organization_onboarding SET business_segment = COALESCE(?, business_segment), sales_model = COALESCE(?, sales_model),
                  whatsapp_choice = COALESCE(?, whatsapp_choice),
                  email_choice = COALESCE(?, email_choice), email_address = CASE WHEN ? = 'VENDOCAS_ADDRESS' THEN NULL ELSE COALESCE(?, email_address) END,
                  updated_at = now() WHERE organization_id = ?""",
                blank(u.businessSegment()), u.salesModel(), u.whatsappChoice(), u.emailChoice(), u.emailChoice(), blank(u.emailAddress()), t.orgId());
        return get();
    }

    /** Finishes the onboarding; every step must have an answer (there is no "skip"). */
    @PostMapping("/complete")
    @RequiresPermission(Permission.ORG_MANAGE)
    @Transactional
    public State complete() {
        var t = TenantContext.current();
        State s = get();
        if (s.completed()) return s;
        Map<String, Object> missing = new LinkedHashMap<>();
        if (s.storeName() == null || s.storeName().isBlank()) missing.put("storeName", "required");
        if (s.businessSegment() == null) missing.put("businessSegment", "required");
        if (s.salesModel() == null) missing.put("salesModel", "required");
        // The customer needs at least one way to reach the store (the e-mail comes pre-filled with the login).
        if (blank(s.alertEmail()) == null && blank(s.alertContact()) == null) missing.put("alertEmail", "required");
        if (s.whatsappChoice() == null) missing.put("whatsappChoice", "required");
        if (s.emailChoice() == null) missing.put("emailChoice", "required");
        if ("OWN_ADDRESS".equals(s.emailChoice()) && s.emailAddress() == null) missing.put("emailAddress", "required");
        if (!missing.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Finish every onboarding step", Map.of("fields", missing));
        // Freeze the pre-filled login e-mail so the footer does not change if another member opens the app.
        jdbc.update("UPDATE organization_settings SET alert_email = COALESCE(alert_email, ?) WHERE organization_id = ?", blank(s.alertEmail()), t.orgId());
        jdbc.update("UPDATE organization_onboarding SET completed_at = now(), updated_at = now() WHERE organization_id = ?", t.orgId());
        // Requests the operator acts on (number purchase, e-mail on the Vendocas domain) are visible in the audit trail.
        audit.user(t.orgId(), t.userId(), "onboarding.completed", "organization", t.orgId(),
                Map.of("whatsapp", s.whatsappChoice(), "email", s.emailChoice(), "salesModel", s.salesModel(), "type", s.businessSegment()));
        return get();
    }

    private static UUID userId() {
        return TenantContext.current().userId();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
