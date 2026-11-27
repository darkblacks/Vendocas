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
 * Mandatory onboarding (cannot be skipped): 1) the store — name, segment and the contact shown in every alert;
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
                        String whatsappChoice, String emailChoice, String emailAddress, String whatsappChannelStatus, String emailChannelStatus) {}

    public record Update(@Size(min = 1, max = 250) String storeName, @Size(max = 80) String businessSegment, @Size(max = 200) String alertContact,
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
                SELECT ob.completed_at, o.trade_name, ob.business_segment, COALESCE(s.alert_contact, o.contact_phone) AS alert_contact,
                       ob.whatsapp_choice, ob.email_choice, ob.email_address,
                       (SELECT status FROM channel_connections WHERE organization_id = o.id AND channel = 'WHATSAPP' ORDER BY updated_at DESC LIMIT 1) AS wa,
                       (SELECT status FROM channel_connections WHERE organization_id = o.id AND channel = 'EMAIL' ORDER BY updated_at DESC LIMIT 1) AS em
                FROM organization_onboarding ob JOIN organizations o ON o.id = ob.organization_id
                LEFT JOIN organization_settings s ON s.organization_id = o.id WHERE ob.organization_id = ?""",
                (rs, i) -> new State(rs.getTimestamp(1) != null, rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)), org);
    }

    /** Saves any step (partial). */
    @PutMapping
    @RequiresPermission(Permission.ORG_MANAGE)
    @Transactional
    public State save(@Valid @RequestBody Update u) {
        var t = TenantContext.current();
        ensureRow(t.orgId());
        if (u.storeName() != null) jdbc.update("UPDATE organizations SET trade_name = ?, updated_at = now() WHERE id = ?", u.storeName().trim(), t.orgId());
        if (u.alertContact() != null) {
            jdbc.update("UPDATE organization_settings SET alert_contact = ? WHERE organization_id = ?", u.alertContact().isBlank() ? null : u.alertContact().trim(), t.orgId());
        }
        jdbc.update("""
                UPDATE organization_onboarding SET business_segment = COALESCE(?, business_segment), whatsapp_choice = COALESCE(?, whatsapp_choice),
                  email_choice = COALESCE(?, email_choice), email_address = CASE WHEN ? = 'VENDOCAS_ADDRESS' THEN NULL ELSE COALESCE(?, email_address) END,
                  updated_at = now() WHERE organization_id = ?""",
                blank(u.businessSegment()), u.whatsappChoice(), u.emailChoice(), u.emailChoice(), blank(u.emailAddress()), t.orgId());
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
        if (s.alertContact() == null || s.alertContact().isBlank()) missing.put("alertContact", "required");
        if (s.whatsappChoice() == null) missing.put("whatsappChoice", "required");
        if (s.emailChoice() == null) missing.put("emailChoice", "required");
        if ("OWN_ADDRESS".equals(s.emailChoice()) && s.emailAddress() == null) missing.put("emailAddress", "required");
        if (!missing.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Finish every onboarding step", Map.of("fields", missing));
        jdbc.update("UPDATE organization_onboarding SET completed_at = now(), updated_at = now() WHERE organization_id = ?", t.orgId());
        // Requests the operator acts on (number purchase, e-mail on the Vendocas domain) are visible in the audit trail.
        audit.user(t.orgId(), t.userId(), "onboarding.completed", "organization", t.orgId(),
                Map.of("whatsapp", s.whatsappChoice(), "email", s.emailChoice()));
        return get();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
