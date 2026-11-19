package com.platform.messaging;

import com.platform.channels.WhatsAppChannelSetup;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/templates")
@Tag(name = "templates")
public class TemplateController {
    private final JdbcTemplate jdbc;
    private final WhatsAppChannelSetup whatsapp;

    public TemplateController(JdbcTemplate jdbc, WhatsAppChannelSetup whatsapp) {
        this.jdbc = jdbc;
        this.whatsapp = whatsapp;
    }

    public record TemplateInput(@NotBlank @Pattern(regexp = "EMAIL|WHATSAPP|SMS") String channel, @NotBlank @Size(max = 120) String name,
                                @NotBlank @Size(max = 35) String locale,
                                @NotBlank @Pattern(regexp = "TRANSACTIONAL|SERVICE_REMINDER|CUSTOMER_CARE|MARKETING|REACTIVATION") String purpose,
                                @Pattern(regexp = "MARKETING|UTILITY|AUTHENTICATION") String waCategory, @Size(max = 300) String subject,
                                @NotBlank @Size(max = 10000) String body, @Size(max = 100000) String htmlBody, UUID mediaAssetId) {}

    public record TemplateView(UUID id, String channel, String name, String locale, String purpose, String waCategory, String subject,
                               String body, String htmlBody, String status, Instant updatedAt, String rejectionReason) {}

    @GetMapping
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public List<TemplateView> list(@RequestParam(required = false) String channel) {
        return jdbc.query("""
                SELECT t.id, t.channel, t.name, t.locale, t.purpose, t.wa_category, t.subject, t.body, t.html_body, t.status, t.updated_at,
                  (SELECT rejection_reason FROM template_approvals a WHERE a.template_id = t.id ORDER BY submitted_at DESC LIMIT 1)
                FROM message_templates t WHERE t.organization_id = ? AND (?::text IS NULL OR t.channel = ?) ORDER BY t.updated_at DESC""",
                (rs, i) -> new TemplateView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getTimestamp(11).toInstant(),
                        rs.getString(12)), TenantContext.current().orgId(), channel, channel);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    public Map<String, UUID> create(@Valid @RequestBody TemplateInput in) {
        UUID org = TenantContext.current().orgId();
        validate(in);
        UUID id = Ids.newId();
        String media = media(org, in.mediaAssetId());
        // Email/SMS templates are usable immediately; WhatsApp templates need Meta approval.
        String status = "WHATSAPP".equals(in.channel()) ? "DRAFT" : "APPROVED";
        jdbc.update("""
                INSERT INTO message_templates (id, organization_id, channel, name, locale, purpose, wa_category, subject, body, html_body, media_object_key, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", id, org, in.channel(), in.name(), in.locale(), in.purpose(), in.waCategory(), in.subject(),
                in.body(), in.htmlBody(), media, status);
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID id, @Valid @RequestBody TemplateInput in) {
        UUID org = TenantContext.current().orgId();
        validate(in);
        // Editing an approved WhatsApp template invalidates the approval: it must be resubmitted.
        int n = jdbc.update("""
                UPDATE message_templates SET name = ?, locale = ?, purpose = ?, wa_category = ?, subject = ?, body = ?, html_body = ?,
                  media_object_key = ?, status = CASE WHEN channel = 'WHATSAPP' THEN 'DRAFT' ELSE status END, updated_at = now()
                WHERE id = ? AND organization_id = ?""", in.name(), in.locale(), in.purpose(), in.waCategory(), in.subject(), in.body(),
                in.htmlBody(), media(org, in.mediaAssetId()), id, org);
        if (n == 0) throw ApiException.notFound("Template");
    }

    @PostMapping("/{id}/submit")
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(@PathVariable UUID id) { whatsapp.submitTemplate(TenantContext.current().orgId(), id); }

    private void validate(TemplateInput in) {
        if ("EMAIL".equals(in.channel()) && (in.subject() == null || in.subject().isBlank())) throw ApiException.badRequest("Email templates need a subject");
        if ("WHATSAPP".equals(in.channel()) && in.waCategory() == null) throw ApiException.badRequest("WhatsApp templates need a category");
        if ("WHATSAPP".equals(in.channel()) && "MARKETING".equals(in.waCategory()) && !List.of("MARKETING", "REACTIVATION").contains(in.purpose())) {
            throw ApiException.badRequest("Marketing category requires a promotional purpose");
        }
        if ("SMS".equals(in.channel()) && in.body().length() > 1600) throw ApiException.badRequest("SMS body too long");
    }

    private String media(UUID org, UUID assetId) {
        if (assetId == null) return null;
        var r = jdbc.queryForList("SELECT object_key FROM media_assets WHERE id = ? AND organization_id = ?", String.class, assetId, org);
        if (r.isEmpty()) throw ApiException.notFound("Media");
        return r.getFirst();
    }
}
