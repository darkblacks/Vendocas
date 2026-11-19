package com.platform.administration;

import com.platform.audit.AuditService;
import com.platform.security.CurrentUser;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.jobs.JobService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform operator back-office. Guarded by PlatformAdminInterceptor (platform role from the DB +
 * MFA; mutations need PLATFORM_ADMIN). Every mutation is audited with actor_type PLATFORM_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final JobService jobs;

    public AdminController(JdbcTemplate jdbc, AuditService audit, JobService jobs) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.jobs = jobs;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("organizations", jdbc.queryForObject("SELECT count(*) FROM organizations", Long.class));
        m.put("subscriptionsByStatus", jdbc.queryForList("SELECT status, count(*) FROM subscriptions GROUP BY status"));
        m.put("mrrByCurrency", jdbc.queryForList("""
                        SELECT p.currency, sum(p.unit_amount_minor) AS mrr_minor FROM subscriptions s JOIN prices p ON p.id = s.price_id
                        WHERE s.status IN ('ACTIVE','PAST_DUE','GRACE_PERIOD') GROUP BY p.currency"""));
        m.put("openInvoices", jdbc.queryForList("SELECT currency, count(*), sum(amount_due_minor) FROM invoices WHERE status = 'OPEN' GROUP BY currency"));
        m.put("usageByChannel30d", jdbc.queryForList("""
                        SELECT channel, count(*) AS messages, sum(charged_minor) AS charged_minor, charged_currency,
                               sum(COALESCE(actual_cost_micros, estimated_cost_micros)) AS provider_cost_usd_micros
                        FROM provider_costs WHERE created_at > now() - interval '30 days' GROUP BY channel, charged_currency"""));
        m.put("countries", jdbc.queryForList("SELECT country_code, count(*) FROM organizations GROUP BY country_code ORDER BY 2 DESC"));
        m.put("channelErrors", jdbc.queryForList("SELECT organization_id, channel, status, status_reason, updated_at FROM channel_connections WHERE status IN ('ERROR','DEGRADED','SUSPENDED') ORDER BY updated_at DESC LIMIT 100"));
        m.put("blockedCampaigns", jdbc.queryForList("SELECT organization_id, id, name, pause_reason FROM campaigns WHERE status = 'PAUSED' AND pause_reason LIKE 'Auto-paused%' LIMIT 100"));
        m.put("openSupportTickets", jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE status IN ('OPEN','PENDING')", Long.class));
        m.put("openPrivacyRequests", jdbc.queryForObject("SELECT count(*) FROM privacy_requests WHERE status NOT IN ('COMPLETED','REJECTED')", Long.class));
        m.put("abuseFlags", jdbc.queryForList("SELECT organization_id, kind, metric_value, created_at FROM abuse_flags WHERE status = 'OPEN' LIMIT 100"));
        return m;
    }

    @GetMapping("/organizations")
    public List<Map<String, Object>> organizations(@RequestParam(required = false) String q) {
        return jdbc.queryForList("""
                SELECT o.id, o.trade_name, o.legal_name, o.country_code, o.status, o.created_at, s.status AS subscription_status, p.code AS plan
                FROM organizations o LEFT JOIN subscriptions s ON s.organization_id = o.id AND s.status NOT IN ('CANCELED','EXPIRED')
                LEFT JOIN plans p ON p.id = s.plan_id
                WHERE (?::text IS NULL OR o.trade_name ILIKE '%' || ? || '%' OR o.legal_name ILIKE '%' || ? || '%') ORDER BY o.created_at DESC LIMIT 200""", q, q, q);
    }

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason, boolean suspend) {}

    @PostMapping("/organizations/{id}/suspension")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void suspend(@PathVariable UUID id, @Valid @RequestBody SuspendRequest r) {
        int n = jdbc.update("UPDATE organizations SET status = ?, updated_at = now() WHERE id = ? AND status <> 'CLOSED'", r.suspend() ? "SUSPENDED_ADMIN" : "ACTIVE", id);
        if (n == 0) throw ApiException.notFound("Organization");
        if (r.suspend()) {
            jdbc.update("UPDATE automation_definitions SET status = 'PAUSED' WHERE organization_id = ? AND status = 'ACTIVE'", id);
            jdbc.update("UPDATE campaigns SET status = 'PAUSED', pause_reason = 'Suspended by platform' WHERE organization_id = ? AND status IN ('ACTIVE','SCHEDULED')", id);
        }
        audit.record(id, CurrentUser.id(), "PLATFORM_ADMIN", r.suspend() ? "admin.org_suspended" : "admin.org_reinstated", "organization", id, Map.of("reason", r.reason()), null);
    }

    public record PriceInput(@NotBlank String planCode, @NotBlank @Pattern(regexp = "USD|BRL|EUR") String currency, @Positive long unitAmountMinor,
                             @Pattern(regexp = "EXCLUSIVE|INCLUSIVE") String taxBehavior, @NotBlank @Pattern(regexp = "^price_[A-Za-z0-9]+$") String stripePriceId) {}

    @GetMapping("/prices")
    public List<Map<String, Object>> prices() {
        return jdbc.queryForList("SELECT pr.id, p.code, pr.currency, pr.unit_amount_minor, pr.tax_behavior, pr.stripe_price_id, pr.active FROM prices pr JOIN plans p ON p.id = pr.plan_id ORDER BY p.tier, pr.currency");
    }

    /** Creates/replaces the active price of a plan in a currency (mirrors a Price created in Stripe). Existing subscriptions keep their price. */
    @PostMapping("/prices")
    @Transactional
    public Map<String, UUID> setPrice(@Valid @RequestBody PriceInput in) {
        UUID planId = jdbc.queryForObject("SELECT id FROM plans WHERE code = ?", UUID.class, in.planCode());
        jdbc.update("UPDATE prices SET active = false WHERE plan_id = ? AND currency = ? AND active", planId, in.currency());
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO prices (id, plan_id, currency, unit_amount_minor, tax_behavior, stripe_price_id) VALUES (?,?,?,?,?,?)",
                id, planId, in.currency(), in.unitAmountMinor(), in.taxBehavior() == null ? "EXCLUSIVE" : in.taxBehavior(), in.stripePriceId());
        audit.record(null, CurrentUser.id(), "PLATFORM_ADMIN", "admin.price_set", "price", id, Map.of("plan", in.planCode(), "currency", in.currency(), "amount", in.unitAmountMinor()), null);
        return Map.of("id", id);
    }

    public record RateInput(@NotBlank @Pattern(regexp = "EMAIL|WHATSAPP|SMS|MMS") String channel, @Pattern(regexp = "[A-Z]{2}") String countryCode,
                            @NotBlank @Pattern(regexp = "ANY|MARKETING|UTILITY|AUTHENTICATION|SERVICE") String category,
                            @NotBlank @Pattern(regexp = "USD|BRL|EUR") String currency, @PositiveOrZero long unitPriceMinor, Long providerCostMicros) {}

    @GetMapping("/usage-rates")
    public List<Map<String, Object>> rates() { return jdbc.queryForList("SELECT * FROM usage_rates WHERE active ORDER BY channel, country_code, currency"); }

    @PostMapping("/usage-rates")
    @Transactional
    public Map<String, UUID> setRate(@Valid @RequestBody RateInput in) {
        jdbc.update("UPDATE usage_rates SET active = false WHERE active AND channel = ? AND COALESCE(country_code,'**') = COALESCE(?,'**') AND message_category = ? AND currency = ?",
                in.channel(), in.countryCode(), in.category(), in.currency());
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO usage_rates (id, channel, country_code, message_category, currency, unit_price_minor, provider_cost_micros) VALUES (?,?,?,?,?,?,?)",
                id, in.channel(), in.countryCode(), in.category(), in.currency(), in.unitPriceMinor(), in.providerCostMicros());
        audit.record(null, CurrentUser.id(), "PLATFORM_ADMIN", "admin.usage_rate_set", "usage_rate", id, Map.of("channel", in.channel(), "currency", in.currency()), null);
        return Map.of("id", id);
    }

    @GetMapping("/capabilities")
    public List<Map<String, Object>> capabilities() { return jdbc.queryForList("SELECT * FROM channel_capabilities ORDER BY country_code, channel, capability"); }

    public record CapabilityInput(@NotBlank String countryCode, @NotBlank String channel, @NotBlank String capability, boolean available, String requires, String notes) {}

    @PutMapping("/capabilities")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setCapability(@Valid @RequestBody CapabilityInput in) {
        jdbc.update("""
                INSERT INTO channel_capabilities (country_code, channel, capability, available, requires, notes, reviewed_at) VALUES (?,?,?,?,?,?, current_date)
                ON CONFLICT (country_code, channel, capability) DO UPDATE SET available = EXCLUDED.available, requires = EXCLUDED.requires, notes = EXCLUDED.notes, reviewed_at = current_date""",
                in.countryCode(), in.channel(), in.capability(), in.available(), in.requires(), in.notes());
        audit.record(null, CurrentUser.id(), "PLATFORM_ADMIN", "admin.capability_set", "capability", in.countryCode() + ":" + in.channel() + ":" + in.capability(),
                Map.of("available", in.available()), null);
    }

    /** Dead-letter investigation: failed jobs, dead automation instances, stuck outbox events. */
    @GetMapping("/dead-letters")
    public Map<String, Object> deadLetters() {
        return Map.of(
                "jobs", jdbc.queryForList("SELECT id, organization_id, job_type, attempts, last_error, updated_at FROM scheduled_jobs WHERE status IN ('DEAD','FAILED') ORDER BY updated_at DESC LIMIT 200"),
                "automationInstances", jdbc.queryForList("SELECT id, organization_id, automation_id, current_step_id, result, updated_at FROM automation_instances WHERE state = 'DEAD_LETTER' ORDER BY updated_at DESC LIMIT 200"),
                "outbox", jdbc.queryForList("SELECT id, organization_id, event_type, attempts, last_error, created_at FROM outbox_events WHERE published_at IS NULL AND attempts >= 12 ORDER BY created_at DESC LIMIT 200"));
    }

    @PostMapping("/dead-letters/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retryJob(@PathVariable UUID id) {
        if (jobs.retryDead(id) == 0) throw ApiException.notFound("Dead job");
        audit.record(null, CurrentUser.id(), "PLATFORM_ADMIN", "admin.job_retried", "job", id, Map.of(), null);
    }

    @PostMapping("/dead-letters/automation-instances/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retryInstance(@PathVariable UUID id) {
        int n = jdbc.update("UPDATE automation_instances SET state = 'READY', attempts = 0, next_run_at = now(), completed_at = NULL WHERE id = ? AND state = 'DEAD_LETTER'", id);
        if (n == 0) throw ApiException.notFound("Dead instance");
        audit.record(null, CurrentUser.id(), "PLATFORM_ADMIN", "admin.instance_retried", "automation_instance", id, Map.of(), null);
    }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs(@RequestParam(required = false) UUID organizationId) {
        return jdbc.queryForList("""
                SELECT id, organization_id, actor_user_id, actor_type, action, target_type, target_id, details::text, created_at FROM audit_logs
                WHERE (?::uuid IS NULL OR organization_id = ?) ORDER BY created_at DESC LIMIT 500""", organizationId, organizationId);
    }

    @GetMapping("/support-tickets")
    public List<Map<String, Object>> tickets() {
        return jdbc.queryForList("SELECT t.*, o.trade_name FROM support_tickets t JOIN organizations o ON o.id = t.organization_id WHERE t.status IN ('OPEN','PENDING') ORDER BY t.created_at");
    }
}
