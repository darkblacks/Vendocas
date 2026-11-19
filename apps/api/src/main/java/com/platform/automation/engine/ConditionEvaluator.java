package com.platform.automation.engine;

import com.platform.automation.model.AutomationDefinition.Condition;
import com.platform.billing.EntitlementService;
import com.platform.channels.ChannelService;
import com.platform.consent.PolicyEngine;
import com.platform.contacts.Contact;
import com.platform.messaging.QuietHours;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
public class ConditionEvaluator {
    private final JdbcTemplate jdbc;
    private final PolicyEngine policy;
    private final ChannelService channels;
    private final EntitlementService entitlements;

    public ConditionEvaluator(JdbcTemplate jdbc, PolicyEngine policy, ChannelService channels, EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.channels = channels;
        this.entitlements = entitlements;
    }

    public record Ctx(UUID orgId, Contact contact, Instant triggerAt, Instant now, ZoneId zone, UUID automationId) {}

    public boolean eval(Condition c, Ctx x) {
        Contact ct = x.contact();
        return switch (c.type()) {
            case "HAS_EMAIL" -> ct.email() != null;
            case "HAS_PHONE" -> ct.phoneE164() != null;
            case "NOT_BLOCKED" -> "ACTIVE".equals(ct.status());
            case "CHANNEL_CONNECTED" -> channels.sendable(x.orgId(), c.channel()).isPresent();
            case "CHANNEL_IN_PLAN" -> entitlements.of(x.orgId()).has(EntitlementService.featureForChannel(c.channel()));
            case "CONSENT" -> policy.evaluate(x.orgId(), ct, c.channel(), c.purpose()).allowed();
            case "NO_PURCHASE_SINCE_TRIGGER" -> !exists("""
                    SELECT 1 FROM contact_events WHERE contact_id = ? AND type = 'PURCHASE_COMPLETED' AND occurred_at >= ?""", ct.id(), x.triggerAt())
                    && (ct.lastPurchaseAt() == null || ct.lastPurchaseAt().isBefore(x.triggerAt()));
            case "NO_REPLY_SINCE_TRIGGER" -> !exists("SELECT 1 FROM contact_events WHERE contact_id = ? AND type = 'REPLY_RECEIVED' AND occurred_at >= ?", ct.id(), x.triggerAt());
            case "NOT_MESSAGED_RECENTLY" -> !exists("""
                    SELECT 1 FROM messages WHERE contact_id = ? AND direction = 'OUTBOUND' AND NOT is_test AND status NOT IN ('BLOCKED','CANCELED')
                      AND (?::text IS NULL OR channel = ?) AND created_at > ?""", ct.id(), c.channel(), c.channel(),
                    x.now().minusSeconds(86400L * (c.days() == null ? 7 : c.days())));
            case "COUNTRY_IN" -> c.countries() != null && c.countries().contains(ct.countryCode());
            case "HAS_TAG" -> ct.tags().contains(c.tag());
            case "NOT_HAS_TAG" -> !ct.tags().contains(c.tag());
            case "LIFETIME_VALUE_GTE" -> ct.lifetimeValueMinor() != null && c.amountMinor() != null && ct.lifetimeValueMinor() >= c.amountMinor();
            case "HAS_EVENT" -> exists("SELECT 1 FROM contact_events WHERE contact_id = ? AND type = ? AND occurred_at > ?", ct.id(), c.eventType(),
                    x.now().minusSeconds(86400L * (c.days() == null ? 365 : c.days())));
            case "WITHIN_HOURS" -> {
                LocalTime t = x.now().atZone(ct.timeZone() != null ? ZoneId.of(ct.timeZone()) : x.zone()).toLocalTime();
                // "within from..to" = NOT inside the complementary window
                yield !QuietHours.inQuiet(t, LocalTime.parse(c.to()), LocalTime.parse(c.from()));
            }
            case "BUDGET_AVAILABLE" -> {
                Long avail = jdbc.queryForObject("SELECT balance_minor - reserved_minor FROM credit_wallets WHERE organization_id = ?", Long.class, x.orgId());
                yield avail != null && avail >= (c.amountMinor() == null ? 1 : c.amountMinor());
            }
            default -> false;
        };
    }

    private boolean exists(String sql, Object... args) {
        Object[] a = args.clone();
        for (int i = 0; i < a.length; i++) if (a[i] instanceof Instant in) a[i] = Timestamp.from(in);
        return !jdbc.queryForList(sql, a).isEmpty();
    }
}
