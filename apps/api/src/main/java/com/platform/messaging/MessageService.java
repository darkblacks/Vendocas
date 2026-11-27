package com.platform.messaging;

import com.platform.billing.CreditWalletService;
import com.platform.billing.EntitlementService;
import com.platform.billing.QuotaService;
import com.platform.markets.AlertFooter;
import com.platform.billing.UsageRates;
import com.platform.channels.ChannelService;
import com.platform.consent.PolicyEngine;
import com.platform.consent.UnsubscribeTokens;
import com.platform.contacts.Contact;
import com.platform.contacts.ContactRepository;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.storage.StorageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Single entry point for every outbound message. All gates run here, in this order, and a failed
 * gate produces a BLOCKED message with an explicit reason (visible in history) instead of a send:
 * subscription & plan → channel connected → compliance policy/consent/suppression → WhatsApp
 * template/window rules → sensitive data → frequency cap → pricing & wallet reservation
 * (incl. daily/monthly spend limits) → quiet hours (defers, does not block).
 */
@Service
public class MessageService {
    public static final Set<String> PROMOTIONAL = Set.of("MARKETING", "REACTIVATION");
    private final JdbcTemplate jdbc;
    private final ContactRepository contacts;
    private final EntitlementService entitlements;
    private final ChannelService channels;
    private final PolicyEngine policy;
    private final UsageRates rates;
    private final CreditWalletService wallet;
    private final UnsubscribeTokens unsubscribe;
    private final StorageService storage;
    private final QuotaService quotas;
    private final Clock clock;

    public MessageService(JdbcTemplate jdbc, ContactRepository contacts, EntitlementService entitlements, ChannelService channels,
                          PolicyEngine policy, UsageRates rates, CreditWalletService wallet, UnsubscribeTokens unsubscribe,
                          StorageService storage, QuotaService quotas, Clock clock) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.entitlements = entitlements;
        this.channels = channels;
        this.policy = policy;
        this.rates = rates;
        this.wallet = wallet;
        this.unsubscribe = unsubscribe;
        this.storage = storage;
        this.quotas = quotas;
        this.clock = clock;
    }

    public record EnqueueResult(UUID messageId, String status, String reason, long costMinor, boolean duplicate) {}

    /** Dry evaluation used by simulation and audience estimates: same gates, nothing written, no money reserved. */
    public record Evaluation(boolean sendable, String reason, long estimatedCostMinor, String currency) {}

    @Transactional(propagation = Propagation.REQUIRED)
    public EnqueueResult enqueue(SendRequest r) {
        var existing = jdbc.queryForList("SELECT id, status, status_reason, cost_reserved_minor FROM messages WHERE organization_id = ? AND idempotency_key = ?",
                r.organizationId(), r.idempotencyKey());
        if (!existing.isEmpty()) {
            var e = existing.getFirst();
            return new EnqueueResult((UUID) e.get("id"), (String) e.get("status"), (String) e.get("status_reason"),
                    ((Number) e.get("cost_reserved_minor")).longValue(), true);
        }
        Contact contact = r.contactId() == null ? null : contacts.find(r.organizationId(), r.contactId()).orElse(null);
        Prepared p = prepare(r, contact);
        UUID id = Ids.newId();
        if (p.blockReason != null) {
            insert(id, r, contact, p, "BLOCKED", p.blockReason, 0, null);
            return new EnqueueResult(id, "BLOCKED", p.blockReason, 0, false);
        }
        long cost = p.cost;
        if (cost > 0) {
            var outcome = wallet.reserve(r.organizationId(), cost, "msg:" + id, "MESSAGE", id);
            if (outcome != CreditWalletService.ReserveOutcome.RESERVED && outcome != CreditWalletService.ReserveOutcome.ALREADY_RESERVED) {
                insert(id, r, contact, p, "BLOCKED", outcome.name(), 0, null);
                return new EnqueueResult(id, "BLOCKED", outcome.name(), 0, false);
            }
        }
        Instant sendAt = p.quietHours ? QuietHours.nextAllowed(clock.instant(), p.zone, p.quietStart, p.quietEnd) : clock.instant();
        if (r.notBefore() != null && r.notBefore().isAfter(sendAt)) sendAt = r.notBefore();
        insert(id, r, contact, p, "QUEUED", null, cost, sendAt);
        return new EnqueueResult(id, "QUEUED", null, cost, false);
    }

    public Evaluation evaluate(SendRequest r) {
        Contact contact = r.contactId() == null ? null : contacts.find(r.organizationId(), r.contactId()).orElse(null);
        Prepared p = prepare(r, contact);
        if (p.blockReason != null) return new Evaluation(false, p.blockReason, 0, p.currency);
        var w = wallet.get(r.organizationId());
        if (p.cost > w.availableMinor()) return new Evaluation(false, "INSUFFICIENT_BALANCE", p.cost, p.currency);
        return new Evaluation(true, "OK", p.cost, p.currency);
    }

    /** Everything resolved for a send; blockReason != null means it must not be sent. */
    static final class Prepared {
        String blockReason, provider, from, fromName, replyTo, to, subject, text, html, mediaKey, currency, waTemplate;
        boolean quotaIncluded;
        Map<String, String> headers = new LinkedHashMap<>();
        List<String> waParams = List.of();
        long cost;
        boolean quietHours;
        ZoneId zone;
        LocalTime quietStart, quietEnd;
        UUID templateId;
        String sendChannel;
    }

    private Prepared prepare(SendRequest r, Contact c) {
        Prepared p = new Prepared();
        UUID org = r.organizationId();
        p.sendChannel = r.channel();
        p.currency = jdbc.queryForObject("SELECT currency FROM credit_wallets WHERE organization_id = ?", String.class, org);
        var ent = entitlements.of(org);
        if (!ent.serviceActive()) return block(p, "SUBSCRIPTION_INACTIVE");
        if (!ent.features().contains(EntitlementService.featureForChannel(r.channel()))) return block(p, "PLAN_EXCLUDES_CHANNEL");
        // Alert plans: automatic alerts only. Campaigns and conversations belong to the Ilimitado plan.
        if (ent.alertsOnly() && !r.test()) {
            if ("CAMPAIGN".equals(r.originType())) return block(p, "PLAN_CAMPAIGNS_REQUIRED");
            if ("MANUAL".equals(r.originType())) return block(p, "PLAN_INBOX_REQUIRED");
        }
        var sendable = channels.sendable(org, r.channel());
        if (sendable.isEmpty()) return block(p, "CHANNEL_NOT_ACTIVE");
        p.provider = sendable.get().provider();
        p.from = sendable.get().fromAddress();
        p.fromName = sendable.get().fromName();
        p.replyTo = sendable.get().connection().config().path("replyTo").asString(null);
        if (c == null) return block(p, "NO_CONTACT");

        PolicyEngine.Decision d = policy.evaluate(org, c, r.channel(), r.purpose());
        if (!d.allowed()) return block(p, d.reason());
        p.to = "EMAIL".equals(r.channel()) ? c.email() : c.phoneE164();

        // ---- content ----
        Map<String, Object> vars = new HashMap<>(c.variables());
        var orgRow = jdbc.queryForMap("SELECT o.trade_name, o.time_zone, o.locale, o.market_code, " + AlertFooter.SQL_COLUMNS
                + " FROM organizations o LEFT JOIN organization_settings s ON s.organization_id = o.id WHERE o.id = ?", org);
        vars.put("business.name", orgRow.get("trade_name"));
        String subject = r.subject(), body = r.body(), html = null;
        String waCategory = null;
        if (r.templateId() != null) {
            var t = jdbc.queryForList("SELECT * FROM message_templates WHERE id = ? AND organization_id = ?", r.templateId(), org);
            if (t.isEmpty()) return block(p, "TEMPLATE_NOT_FOUND");
            var tpl = t.getFirst();
            if (!r.channel().equals(tpl.get("channel")) && !("MMS".equals(r.channel()) && "SMS".equals(tpl.get("channel")))) return block(p, "TEMPLATE_CHANNEL_MISMATCH");
            if ("WHATSAPP".equals(r.channel()) && !"APPROVED".equals(tpl.get("status"))) return block(p, "WHATSAPP_TEMPLATE_NOT_APPROVED");
            if ("DISABLED".equals(tpl.get("status")) || "PAUSED".equals(tpl.get("status"))) return block(p, "TEMPLATE_" + tpl.get("status"));
            subject = (String) tpl.get("subject");
            body = (String) tpl.get("body");
            html = (String) tpl.get("html_body");
            waCategory = (String) tpl.get("wa_category");
            p.waTemplate = (String) tpl.get("name");
            if (tpl.get("media_object_key") != null) p.mediaKey = (String) tpl.get("media_object_key");
            p.templateId = r.templateId();
            if ("WHATSAPP".equals(r.channel()) && "MARKETING".equals(waCategory) && !PROMOTIONAL.contains(r.purpose())) {
                return block(p, "TEMPLATE_CATEGORY_PURPOSE_MISMATCH");
            }
        }
        if (body == null || body.isBlank()) return block(p, "EMPTY_BODY");
        if (PROMOTIONAL.contains(r.purpose())) {
            Set<String> sensitive = new HashSet<>();
            jdbc.queryForList("SELECT key FROM contact_custom_fields WHERE organization_id = ? AND is_sensitive", String.class, org)
                    .forEach(k -> sensitive.add("contact.custom." + k));
            if (TemplateRenderer.references(body, sensitive) || TemplateRenderer.references(subject, sensitive) || TemplateRenderer.references(html, sensitive)) {
                return block(p, "SENSITIVE_DATA_IN_PROMOTION");
            }
        }
        if ("WHATSAPP".equals(r.channel())) {
            if (r.templateId() == null) {
                Instant lastInbound = jdbc.query("SELECT last_inbound_at FROM conversations WHERE organization_id = ? AND contact_id = ? AND channel = 'WHATSAPP'",
                        (rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), org, c.id()).stream().filter(Objects::nonNull).findFirst().orElse(null);
                if (lastInbound == null || lastInbound.isBefore(clock.instant().minus(Duration.ofHours(24)))) return block(p, "WHATSAPP_OUTSIDE_SERVICE_WINDOW");
                p.waTemplate = null;
            } else {
                p.waParams = com.platform.channels.WhatsAppChannelSetup.variableOrder(body).stream()
                        .map(v -> String.valueOf(vars.getOrDefault(v, ""))).toList();
            }
        }
        if (r.mediaAssetId() != null) {
            var media = jdbc.queryForList("SELECT object_key FROM media_assets WHERE id = ? AND organization_id = ?", String.class, r.mediaAssetId(), org);
            if (media.isEmpty()) return block(p, "MEDIA_NOT_FOUND");
            p.mediaKey = media.getFirst();
        }
        if (p.mediaKey != null && "SMS".equals(r.channel())) p.sendChannel = "MMS";
        if ("MMS".equals(p.sendChannel) && !capability(org, d.recipientCountry(), "SMS", "MMS")) return block(p, "MMS_NOT_AVAILABLE_IN_" + d.recipientCountry());

        p.subject = TemplateRenderer.render(subject, vars, false);
        p.text = TemplateRenderer.render(body, vars, false);
        String alertFooter = null;
        if (ent.alertsOnly()) {
            // WhatsApp templates carry the footer as an approved component (see WhatsAppChannelSetup); free text gets it appended.
            String footerLocale = c.locale() != null ? c.locale() : (String) orgRow.get("locale");
            var footerContact = AlertFooter.Contact.of(orgRow);
            alertFooter = AlertFooter.text(footerLocale, footerContact);
            String inText = "SMS".equals(r.channel()) || "MMS".equals(p.sendChannel) ? AlertFooter.sms(footerLocale, footerContact) : alertFooter;
            if (!"WHATSAPP".equals(r.channel()) || p.waTemplate == null) p.text = p.text + "\n\n" + inText;
        }
        if ("EMAIL".equals(r.channel())) {
            if (p.subject == null || p.subject.isBlank()) return block(p, "EMPTY_SUBJECT");
            String footer = null;
            if (PROMOTIONAL.contains(r.purpose()) || d.requiresUnsubscribe()) {
                String url = unsubscribe.url(org, c.id(), "EMAIL");
                p.headers.put("List-Unsubscribe", "<" + url + ">");
                p.headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
                footer = "<a href=\"" + url + "\">Unsubscribe</a>";
                p.text = p.text + "\n\n—\nUnsubscribe: " + url;
            }
            String alertHtml = alertFooter == null ? "" : "<p style=\"font-size:12px;color:#555\">" + HtmlUtils.htmlEscape(alertFooter).replace("\n", "<br>") + "</p>";
            p.html = html != null ? TemplateRenderer.render(html, vars, true) + alertHtml + (footer == null ? "" : "<p style=\"font-size:12px\">" + footer + "</p>")
                    : TemplateRenderer.textToHtml(TemplateRenderer.render(body, vars, false) + (alertFooter == null ? "" : "\n\n" + alertFooter), footer);
        } else if (("SMS".equals(r.channel()) || "MMS".equals(p.sendChannel)) && PROMOTIONAL.contains(r.purpose())) {
            if (!p.text.toUpperCase(Locale.ROOT).contains("STOP")) p.text = p.text + "\nReply STOP to opt out";
        }

        // ---- frequency cap (promotional only) ----
        if (PROMOTIONAL.contains(r.purpose()) && !r.test()) {
            int cap = jdbc.queryForObject("SELECT default_frequency_cap FROM organization_settings WHERE organization_id = ?", Integer.class, org);
            Integer recent = jdbc.queryForObject("""
                    SELECT count(*) FROM messages WHERE organization_id = ? AND contact_id = ? AND direction = 'OUTBOUND' AND NOT is_test
                      AND purpose IN ('MARKETING','REACTIVATION') AND status NOT IN ('BLOCKED','CANCELED','FAILED') AND created_at > now() - interval '7 days'""",
                    Integer.class, org, c.id());
            if (recent != null && recent >= cap) return block(p, "FREQUENCY_CAP");
            if (r.frequencyCapDaysOverride() != null) {
                Integer inWindow = jdbc.queryForObject("""
                        SELECT count(*) FROM messages WHERE organization_id = ? AND contact_id = ? AND direction = 'OUTBOUND' AND NOT is_test
                          AND origin_type = ? AND origin_id = ? AND status NOT IN ('BLOCKED','CANCELED','FAILED') AND created_at > now() - make_interval(days => ?)""",
                        Integer.class, org, c.id(), r.originType(), r.originId(), r.frequencyCapDaysOverride());
                if (inWindow != null && inWindow > 0) return block(p, "CAMPAIGN_FREQUENCY_CAP");
            }
        }

        // ---- pricing ----
        String category = "WHATSAPP".equals(r.channel()) ? (waCategory == null ? "SERVICE" : waCategory) : "ANY";
        QuotaService.Mode mode = r.test() ? QuotaService.Mode.PAY_PER_USE : quotas.decide(org, ent.planId(), (String) orgRow.get("market_code"), p.sendChannel);
        if (mode == QuotaService.Mode.INCLUDED) {
            p.quotaIncluded = true;
            p.cost = 0;
        } else {
            var rate = rates.find(p.sendChannel, d.recipientCountry(), category, p.currency);
            if (rate.isEmpty()) return block(p, mode == QuotaService.Mode.EXCEEDED ? "PLAN_QUOTA_EXCEEDED" : "USAGE_RATE_NOT_CONFIGURED");
            int units = "SMS".equals(p.sendChannel) ? SmsSegments.count(p.text) : 1;
            p.cost = rate.get().unitPriceMinor() * units;
        }

        // ---- quiet hours (in the recipient's zone when known) ----
        if (d.quietHoursEnforced() && !r.test()) {
            var s = jdbc.queryForMap("SELECT quiet_hours_start, quiet_hours_end FROM organization_settings WHERE organization_id = ?", org);
            p.quietHours = true;
            p.quietStart = ((java.sql.Time) s.get("quiet_hours_start")).toLocalTime();
            p.quietEnd = ((java.sql.Time) s.get("quiet_hours_end")).toLocalTime();
            p.zone = ZoneId.of(c.timeZone() != null ? c.timeZone() : (String) orgRow.get("time_zone"));
        }
        return p;
    }

    private boolean capability(UUID org, String country, String channel, String capability) {
        return Boolean.TRUE.equals(jdbc.query("SELECT available FROM channel_capabilities WHERE country_code = ? AND channel = ? AND capability = ?",
                (rs, i) -> rs.getBoolean(1), country, channel, capability).stream().findFirst().orElse(false));
    }

    private static Prepared block(Prepared p, String reason) {
        p.blockReason = reason;
        return p;
    }

    private void insert(UUID id, SendRequest r, Contact c, Prepared p, String status, String reason, long cost, Instant queuedAt) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!p.headers.isEmpty()) meta.put("headers", p.headers);
        if (p.waTemplate != null) meta.put("waTemplate", p.waTemplate);
        if (!p.waParams.isEmpty()) meta.put("waParams", p.waParams);
        if (p.html != null) meta.put("html", p.html);
        if (p.replyTo != null) meta.put("replyTo", p.replyTo);
        if (p.fromName != null) meta.put("fromName", p.fromName);
        jdbc.update("""
                INSERT INTO messages (id, organization_id, contact_id, direction, channel, purpose, status, status_reason, to_address, from_address,
                    template_id, subject, body, media_object_key, provider, origin_type, origin_id, idempotency_key, is_test, cost_reserved_minor,
                    cost_currency, queued_at, render_meta, quota_included)
                VALUES (?,?,?, 'OUTBOUND', ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?::jsonb, ?)""",
                id, r.organizationId(), c == null ? null : c.id(), p.sendChannel, r.purpose(), status, reason, p.to, p.from, p.templateId,
                p.subject, p.text, p.mediaKey, p.provider, r.originType(), r.originId(), r.idempotencyKey(), r.test(), cost, p.currency,
                queuedAt == null ? null : Timestamp.from(queuedAt), Json.write(meta), p.quotaIncluded && "QUEUED".equals(status));
    }
}
