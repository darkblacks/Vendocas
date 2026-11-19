package com.platform.messaging;

import com.platform.consent.ConsentService;
import com.platform.contacts.Phones;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.crypto.Tokens;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Inbound provider webhooks. Each is authenticated by the provider's own signature scheme before
 * any processing, and deduplicated (event receipts / message_events unique keys) so redelivery
 * and out-of-order arrival are harmless.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Hidden
public class ProviderWebhookController {
    private static final Logger log = LoggerFactory.getLogger(ProviderWebhookController.class);
    private final AppProperties props;
    private final MessageStatusService statuses;
    private final InboundService inbound;
    private final ConsentService consents;
    private final SnsVerifier sns;
    private final JdbcTemplate jdbc;

    public ProviderWebhookController(AppProperties props, MessageStatusService statuses, InboundService inbound, ConsentService consents,
                                     SnsVerifier sns, JdbcTemplate jdbc) {
        this.props = props;
        this.statuses = statuses;
        this.inbound = inbound;
        this.consents = consents;
        this.sns = sns;
        this.jdbc = jdbc;
    }

    // ---------------- Amazon SES (via SNS) ----------------
    @PostMapping("/ses")
    @Transactional
    public ResponseEntity<Void> ses(@RequestBody String raw) {
        JsonNode m = Json.read(raw);
        String topic = props.aws().snsTopicArn();
        if (topic == null || topic.isBlank() || !topic.equals(m.path("TopicArn").asString()) || !sns.verify(m)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid SNS message");
        }
        if ("SubscriptionConfirmation".equals(m.path("Type").asString())) {
            sns.confirmSubscription(m.path("SubscribeURL").asString());
            return ResponseEntity.ok().build();
        }
        if (!receipt("SES", m.path("MessageId").asString(), raw)) return ResponseEntity.ok().build();
        JsonNode ev = Json.read(m.path("Message").asString("{}"));
        String type = ev.path("eventType").asString(ev.path("notificationType").asString(""));
        String sesId = ev.path("mail").path("messageId").asString();
        Instant at = Instant.now();
        switch (type) {
            case "Send" -> statuses.apply("SES", sesId, "SENT", at, null, Map.of());
            case "Delivery" -> statuses.apply("SES", sesId, "DELIVERED", at, null, Map.of());
            case "Open" -> statuses.apply("SES", sesId, "READ", at, null, Map.of());
            case "Bounce" -> {
                boolean permanent = "Permanent".equals(ev.path("bounce").path("bounceType").asString());
                statuses.apply("SES", sesId, "BOUNCED", at, null, Map.of("reason", "BOUNCE_" + ev.path("bounce").path("bounceType").asString())).ifPresent(r -> {
                    if (permanent) suppress(r.orgId(), "EMAIL", r.to(), "HARD_BOUNCE", "ALL");
                });
            }
            case "Complaint" -> statuses.apply("SES", sesId, "COMPLAINED", at, null, Map.of()).ifPresent(r -> {
                suppress(r.orgId(), "EMAIL", r.to(), "COMPLAINT", "MARKETING");
                if (r.contactId() != null) consents.optOutMarketing(r.orgId(), r.contactId(), "EMAIL", r.to(), "COMPLAINT");
            });
            case "Reject", "RenderingFailure" -> statuses.apply("SES", sesId, "FAILED", at, null, Map.of("reason", type));
            default -> { }
        }
        return ResponseEntity.ok().build();
    }

    // ---------------- Meta WhatsApp Cloud API ----------------
    @GetMapping(value = "/whatsapp", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> waVerify(@RequestParam("hub.mode") String mode, @RequestParam("hub.verify_token") String token,
                                           @RequestParam("hub.challenge") String challenge) {
        String expected = props.meta().webhookVerifyToken();
        if ("subscribe".equals(mode) && expected != null && !expected.isBlank() && Tokens.constantTimeEquals(expected, token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).build();
    }

    @PostMapping("/whatsapp")
    @Transactional
    public ResponseEntity<Void> wa(@RequestBody byte[] rawBytes, @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        String secret = props.meta().appSecret();
        if (secret == null || secret.isBlank()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Meta app secret not configured");
        String expected = "sha256=" + HexFormat.of().formatHex(Tokens.hmac("HmacSHA256", secret.getBytes(StandardCharsets.UTF_8), rawBytes));
        if (!Tokens.constantTimeEquals(expected, signature)) throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid signature");
        String raw = new String(rawBytes, StandardCharsets.UTF_8);
        if (!receipt("META", Tokens.sha256Hex(rawBytes), raw)) return ResponseEntity.ok().build();
        JsonNode root = Json.read(raw);
        for (JsonNode entry : root.path("entry")) {
            String wabaId = entry.path("id").asString();
            for (JsonNode change : entry.path("changes")) {
                JsonNode v = change.path("value");
                switch (change.path("field").asString()) {
                    case "messages" -> waMessages(v);
                    case "message_template_status_update" -> waTemplate(wabaId, v);
                    case "phone_number_quality_update" -> jdbc.update("""
                            UPDATE sender_identities SET messaging_limit = ?, updated_at = now() WHERE kind = 'WHATSAPP_NUMBER'
                              AND value = ?""", v.path("current_limit").asString(null), "+" + v.path("display_phone_number").asString("").replaceAll("\\D", ""));
                    default -> { }
                }
            }
        }
        return ResponseEntity.ok().build();
    }

    private void waMessages(JsonNode v) {
        String phoneNumberId = v.path("metadata").path("phone_number_id").asString();
        for (JsonNode s : v.path("statuses")) {
            String st = s.path("status").asString("").toUpperCase(Locale.ROOT);
            Instant at = Instant.ofEpochSecond(s.path("timestamp").asLong(Instant.now().getEpochSecond()));
            Map<String, Object> detail = new HashMap<>();
            if (s.has("errors")) detail.put("reason", "META_" + s.path("errors").path(0).path("code").asString() + " " + s.path("errors").path(0).path("title").asString(""));
            statuses.apply("META_CLOUD", s.path("id").asString(), st, at, s.path("id").asString() + ":" + st, detail);
        }
        if (!v.has("messages")) return;
        var org = jdbc.queryForList("SELECT organization_id FROM sender_identities WHERE kind = 'WHATSAPP_NUMBER' AND external_id = ?", UUID.class, phoneNumberId);
        if (org.isEmpty()) {
            log.warn("WhatsApp message for unknown phone_number_id {}", phoneNumberId);
            return;
        }
        String to = jdbc.queryForObject("SELECT value FROM sender_identities WHERE kind = 'WHATSAPP_NUMBER' AND external_id = ?", String.class, phoneNumberId);
        for (JsonNode msg : v.path("messages")) {
            String text = switch (msg.path("type").asString()) {
                case "text" -> msg.path("text").path("body").asString("");
                case "button" -> msg.path("button").path("text").asString("");
                case "interactive" -> msg.path("interactive").path("button_reply").path("title").asString("");
                default -> "[" + msg.path("type").asString() + "]";
            };
            String from = "+" + msg.path("from").asString().replaceAll("\\D", "");
            inbound.receive(org.getFirst(), "WHATSAPP", from, to, text, "META_CLOUD", msg.path("id").asString(),
                    Instant.ofEpochSecond(msg.path("timestamp").asLong(Instant.now().getEpochSecond())));
        }
    }

    private void waTemplate(String wabaId, JsonNode v) {
        String event = v.path("event").asString("");
        String status = switch (event) { case "APPROVED" -> "APPROVED"; case "REJECTED" -> "REJECTED"; case "PAUSED" -> "PAUSED"; case "DISABLED" -> "DISABLED"; default -> null; };
        if (status == null) return;
        String providerTemplateId = v.path("message_template_id").asString();
        var tpl = jdbc.queryForList("""
                SELECT a.template_id FROM template_approvals a JOIN channel_connections c ON c.organization_id = a.organization_id
                WHERE a.provider_template_id = ? AND c.channel = 'WHATSAPP' AND c.external_account_id = ? ORDER BY a.submitted_at DESC LIMIT 1""",
                UUID.class, providerTemplateId, wabaId);
        if (tpl.isEmpty()) return;
        jdbc.update("UPDATE message_templates SET status = ?, updated_at = now() WHERE id = ?", status, tpl.getFirst());
        jdbc.update("UPDATE template_approvals SET status = ?, rejection_reason = ?, decided_at = now() WHERE template_id = ? AND provider_template_id = ?",
                status, v.path("reason").asString(null), tpl.getFirst(), providerTemplateId);
    }

    // ---------------- Twilio SMS ----------------
    @PostMapping(value = "/twilio/sms-status", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Transactional
    public ResponseEntity<Void> twilioStatus(@RequestParam Map<String, String> params, HttpServletRequest req) {
        verifyTwilio(params, req);
        String st = switch (params.getOrDefault("MessageStatus", "")) {
            case "sent" -> "SENT";
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            case "failed", "undelivered" -> "FAILED";
            default -> null;
        };
        if (st != null) {
            Map<String, Object> d = params.containsKey("ErrorCode") ? Map.of("reason", "TWILIO_" + params.get("ErrorCode")) : Map.of();
            statuses.apply("TWILIO", params.get("MessageSid"), st, Instant.now(), params.get("MessageSid") + ":" + st, d);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/twilio/sms-inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    @Transactional
    public String twilioInbound(@RequestParam Map<String, String> params, HttpServletRequest req) {
        verifyTwilio(params, req);
        String to = params.get("To");
        var org = jdbc.queryForList("SELECT organization_id FROM sender_identities WHERE kind = 'SMS_NUMBER' AND value = ?", UUID.class, to);
        if (!org.isEmpty()) {
            String from = Phones.toE164(params.get("From"), null).orElse(params.get("From"));
            inbound.receive(org.getFirst(), "SMS", from, to, params.getOrDefault("Body", ""), "TWILIO", params.get("MessageSid"), Instant.now());
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>";
    }

    void verifyTwilio(Map<String, String> params, HttpServletRequest req) {
        if (!props.twilio().configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Twilio not configured");
        String url = props.apiPublicUrl() + req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        if (!TwilioSignature.valid(props.twilio().authToken(), url, params, req.getHeader("X-Twilio-Signature"))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid Twilio signature");
        }
    }

    private boolean receipt(String provider, String eventId, String raw) {
        return jdbc.update("""
                INSERT INTO external_event_receipts (id, provider, provider_event_id, status, payload_sha256, processed_at)
                VALUES (?,?,?, 'PROCESSED', ?, now()) ON CONFLICT (provider, provider_event_id) DO NOTHING""",
                Ids.newId(), provider, eventId, Tokens.sha256Hex(raw)) == 1;
    }

    private void suppress(UUID orgId, String channel, String address, String reason, String scope) {
        if (address == null) return;
        jdbc.update("""
                INSERT INTO suppression_list (id, organization_id, channel, address, reason, scope) VALUES (?,?,?,?,?,?)
                ON CONFLICT (organization_id, channel, address) DO UPDATE SET scope = CASE WHEN EXCLUDED.scope = 'ALL' THEN 'ALL' ELSE suppression_list.scope END""",
                Ids.newId(), orgId, channel, address, reason, scope);
    }
}
