package com.platform.messaging;

import com.platform.billing.CreditWalletService;
import com.platform.billing.EntitlementService;
import com.platform.channels.ChannelService;
import com.platform.consent.ConsentService;
import com.platform.consent.PolicyEngine;
import com.platform.contacts.ContactRepository;
import com.platform.messaging.providers.CircuitBreaker;
import com.platform.messaging.providers.MessageProvider;
import com.platform.notifications.NotificationService;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.storage.StorageService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends QUEUED messages. Delivery semantics are AT-MOST-ONCE per message: the row is moved to
 * SUBMITTED and committed BEFORE the provider call. If the process dies during the call, the
 * outcome is unknown and the message is marked FAILED(UNKNOWN_OUTCOME) by recovery instead of
 * being re-sent — a missed message is preferable to a duplicate one to a consumer.
 * Retryable provider errors (throttling, 5xx, network before the request) go back to QUEUED
 * with exponential backoff, up to MAX_ATTEMPTS.
 */
@Component
public class MessageDispatcher {
    static final int MAX_ATTEMPTS = 5;
    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, MessageProvider> providers;
    private final ChannelService channels;
    private final EntitlementService entitlements;
    private final PolicyEngine policy;
    private final ContactRepository contacts;
    private final CreditWalletService wallet;
    private final ConsentService consents;
    private final CircuitBreaker breaker;
    private final StorageService storage;
    private final NotificationService notifications;
    private final AppProperties props;
    private final MeterRegistry meters;

    public MessageDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, List<MessageProvider> providers, ChannelService channels,
                             EntitlementService entitlements, PolicyEngine policy, ContactRepository contacts, CreditWalletService wallet,
                             ConsentService consents, CircuitBreaker breaker, StorageService storage, NotificationService notifications,
                             AppProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.providers = providers.stream().collect(Collectors.toMap(MessageProvider::provider, Function.identity()));
        this.channels = channels;
        this.entitlements = entitlements;
        this.policy = policy;
        this.contacts = contacts;
        this.wallet = wallet;
        this.consents = consents;
        this.breaker = breaker;
        this.storage = storage;
        this.notifications = notifications;
        this.props = props;
        this.meters = meters;
    }

    record Claimed(UUID id, UUID orgId, UUID contactId, String channel, String purpose, String provider, String to, String from, String subject,
                   String body, String mediaKey, String originType, UUID originId, long reserved, JsonNode meta, boolean test) {}

    @Scheduled(fixedDelayString = "${app.workers.dispatch-poll-ms:500}")
    public void poll() {
        if (!props.workers().enabled()) return;
        dispatchBatch(props.workers().batchSize());
    }

    @Scheduled(fixedDelayString = "${app.workers.recovery-poll-ms:60000}")
    public void recoverPoll() {
        if (props.workers().enabled()) recoverUnknownOutcomes();
    }

    public int dispatchBatch(int limit) {
        List<Claimed> batch = tx.execute(s -> jdbc.query("""
                UPDATE messages SET status = 'SUBMITTED', updated_at = now(), version = version + 1
                WHERE id IN (SELECT id FROM messages WHERE status = 'QUEUED' AND queued_at <= now() ORDER BY queued_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, organization_id, contact_id, channel, purpose, provider, to_address, from_address, subject, body, media_object_key,
                          origin_type, origin_id, cost_reserved_minor, render_meta::text, is_test""",
                (rs, i) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.getString(12), rs.getObject(13, UUID.class), rs.getLong(14), Json.read(rs.getString(15)), rs.getBoolean(16)), limit));
        if (batch == null) return 0;
        for (Claimed m : batch) {
            try {
                send(m);
            } catch (RuntimeException e) {
                log.error("Dispatch of message {} crashed", m.id(), e);
                finish(m, "FAILED", "INTERNAL_ERROR", null, false);
            }
        }
        return batch.size();
    }

    private void send(Claimed m) {
        // Re-check at send time: things may have changed since enqueue (suspension, opt-out, pause).
        String recheck = recheck(m);
        if (recheck != null) {
            finish(m, "CANCELED", recheck, null, false);
            return;
        }
        MessageProvider provider = providers.get(m.provider());
        if (provider == null || !provider.configured()) {
            finish(m, "FAILED", "PROVIDER_NOT_CONFIGURED", null, false);
            return;
        }
        if (!breaker.allow(m.provider())) {
            requeue(m, Duration.ofSeconds(60), "CIRCUIT_OPEN", "Provider circuit open", false);
            return;
        }
        var sendable = channels.sendable(m.orgId(), m.channel());
        if (sendable.isEmpty()) {
            finish(m, "CANCELED", "CHANNEL_NOT_ACTIVE", null, false);
            return;
        }
        String mediaUrl = m.mediaKey() == null ? null : storage.presignedGet(m.mediaKey(), Duration.ofDays(7));
        MessageProvider.Template template = null;
        if (m.meta().has("waTemplate")) {
            List<String> params = new ArrayList<>();
            m.meta().path("waParams").forEach(n -> params.add(n.asString()));
            String lang = jdbc.queryForObject("SELECT t.locale FROM messages x JOIN message_templates t ON t.id = x.template_id WHERE x.id = ?", String.class, m.id());
            template = new MessageProvider.Template(m.meta().path("waTemplate").asString(), lang.replace('-', '_'), params, mediaUrl);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        m.meta().path("headers").properties().forEach(e -> headers.put(e.getKey(), e.getValue().asString()));
        var out = new MessageProvider.Outbound(m.id(), m.to(), m.from(), m.meta().path("fromName").asString(null),
                m.meta().path("replyTo").asString(null), m.subject(), m.body(), m.meta().path("html").asString(null),
                template == null ? mediaUrl : null, template, headers);
        long started = System.nanoTime();
        MessageProvider.Result res = provider.send(out, sendable.get().connection());
        int latency = (int) ((System.nanoTime() - started) / 1_000_000);
        meters.timer("messages.provider.latency", "provider", m.provider()).record(Duration.ofMillis(latency));
        attempt(m.id(), res, latency);
        switch (res.outcome()) {
            case ACCEPTED -> {
                breaker.success(m.provider());
                tx.executeWithoutResult(s -> {
                    jdbc.update("UPDATE messages SET provider_message_id = ?, sent_at = now(), cost_charged_minor = ?, updated_at = now() WHERE id = ?",
                            res.providerMessageId(), m.reserved(), m.id());
                    if (m.reserved() > 0) wallet.debit(m.orgId(), m.reserved(), m.reserved(), "msg:" + m.id(), "MESSAGE", m.id());
                    recordProviderCost(m);
                    touchConversation(m);
                });
                meters.counter("messages.submitted", "channel", m.channel()).increment();
            }
            case RETRYABLE -> {
                breaker.failure(m.provider());
                int attempts = attempts(m.id());
                if (attempts >= MAX_ATTEMPTS) finish(m, "FAILED", res.errorCode() + ": " + res.errorMessage(), null, false);
                else requeue(m, Duration.ofSeconds((long) Math.pow(4, attempts) * 15), res.errorCode(), res.errorMessage(), true);
            }
            case PERMANENT -> {
                finish(m, "FAILED", res.errorCode() + ": " + truncate(res.errorMessage()), null, res.recipientOptedOut());
                if (res.credentialsInvalid()) {
                    channels.find(m.orgId(), "MMS".equals(m.channel()) ? "SMS" : m.channel()).ifPresent(c -> {
                        channels.setStatus(c.id(), "ERROR", "Provider rejected our credentials", "Reconnect the channel");
                        notifications.notify(m.orgId(), "CRITICAL", "CHANNEL_ERROR", "Channel needs attention",
                                m.channel() + " was disconnected by the provider. Reconnect it to resume sending.", "channel-error:" + c.id() + ":" + java.time.LocalDate.now());
                    });
                }
            }
        }
    }

    private String recheck(Claimed m) {
        if (!entitlements.of(m.orgId()).serviceActive()) return "SUBSCRIPTION_INACTIVE";
        if ("AUTOMATION".equals(m.originType())) {
            String st = jdbc.queryForList("""
                    SELECT d.status FROM automation_instances i JOIN automation_definitions d ON d.id = i.automation_id WHERE i.id = ?""",
                    String.class, m.originId()).stream().findFirst().orElse("ACTIVE");
            if (!"ACTIVE".equals(st)) return "AUTOMATION_" + st;
        }
        if ("CAMPAIGN".equals(m.originType())) {
            String st = jdbc.queryForList("SELECT c.status FROM campaign_runs r JOIN campaigns c ON c.id = r.campaign_id WHERE r.id = ?",
                    String.class, m.originId()).stream().findFirst().orElse("ACTIVE");
            if (!"ACTIVE".equals(st) && !"SCHEDULED".equals(st)) return "CAMPAIGN_" + st;
        }
        if (m.contactId() != null) {
            var c = contacts.find(m.orgId(), m.contactId());
            if (c.isEmpty()) return "CONTACT_DELETED";
            var d = policy.evaluate(m.orgId(), c.get(), m.channel(), m.purpose());
            if (!d.allowed()) return d.reason();
        }
        return null;
    }

    private void requeue(Claimed m, Duration delay, String code, String msg, boolean countAttempt) {
        tx.executeWithoutResult(s -> jdbc.update("UPDATE messages SET status = 'QUEUED', queued_at = ?, status_reason = ?, updated_at = now() WHERE id = ?",
                Timestamp.from(Instant.now().plus(delay)), code, m.id()));
        if (!countAttempt) attempt(m.id(), new MessageProvider.Result(MessageProvider.Outcome.RETRYABLE, null, "CIRCUIT_OPEN", msg, false, false), 0);
    }

    private void finish(Claimed m, String status, String reason, String providerId, boolean optedOut) {
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE messages SET status = ?, status_reason = ?, failed_at = CASE WHEN ? = 'FAILED' THEN now() END, updated_at = now() WHERE id = ?",
                    status, truncate(reason), status, m.id());
            wallet.release(m.orgId(), m.reserved(), "msg:" + m.id(), "MESSAGE", m.id());
            if (optedOut && m.contactId() != null) consents.optOutMarketing(m.orgId(), m.contactId(), "MMS".equals(m.channel()) ? "SMS" : m.channel(), m.to(), "INBOUND_STOP");
        });
        meters.counter("messages." + status.toLowerCase(Locale.ROOT), "channel", m.channel()).increment();
    }

    /** Messages stuck in SUBMITTED without a provider id: outcome unknown → FAILED, never re-sent. */
    public int recoverUnknownOutcomes() {
        List<Map<String, Object>> stuck = jdbc.queryForList("""
                SELECT id, organization_id, cost_reserved_minor FROM messages
                WHERE status = 'SUBMITTED' AND provider_message_id IS NULL AND updated_at < now() - interval '5 minutes' LIMIT 500""");
        for (var r : stuck) {
            UUID id = (UUID) r.get("id");
            UUID org = (UUID) r.get("organization_id");
            long reserved = ((Number) r.get("cost_reserved_minor")).longValue();
            tx.executeWithoutResult(s -> {
                int n = jdbc.update("UPDATE messages SET status = 'FAILED', status_reason = 'UNKNOWN_OUTCOME_NOT_RETRIED', failed_at = now() WHERE id = ? AND status = 'SUBMITTED' AND provider_message_id IS NULL", id);
                if (n == 1) wallet.release(org, reserved, "msg:" + id, "MESSAGE", id);
            });
        }
        return stuck.size();
    }

    private int attempts(UUID id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM message_attempts WHERE message_id = ? AND error_code IS DISTINCT FROM 'CIRCUIT_OPEN'", Integer.class, id);
        return n == null ? 0 : n;
    }

    private void attempt(UUID id, MessageProvider.Result r, int latency) {
        String outcome = switch (r.outcome()) {
            case ACCEPTED -> "ACCEPTED";
            case RETRYABLE -> "CIRCUIT_OPEN".equals(r.errorCode()) ? "CIRCUIT_OPEN" : "RETRYABLE_ERROR";
            case PERMANENT -> "PERMANENT_ERROR";
        };
        jdbc.update("""
                INSERT INTO message_attempts (id, message_id, attempt_no, outcome, error_code, error_message, latency_ms)
                VALUES (?, ?, (SELECT COALESCE(max(attempt_no),0)+1 FROM message_attempts WHERE message_id = ?), ?, ?, ?, ?)""",
                Ids.newId(), id, id, outcome, r.errorCode(), truncate(r.errorMessage()), latency);
    }

    private void recordProviderCost(Claimed m) {
        jdbc.update("""
                INSERT INTO provider_costs (id, organization_id, provider, channel, reference_type, reference_id, estimated_cost_micros, charged_minor, charged_currency)
                SELECT ?, ?, ?, ?, 'MESSAGE', ?, (SELECT provider_cost_micros FROM usage_rates WHERE active AND channel = ? LIMIT 1), ?, cost_currency
                FROM messages WHERE id = ? ON CONFLICT DO NOTHING""",
                Ids.newId(), m.orgId(), m.provider(), m.channel(), m.id(), m.channel(), m.reserved(), m.id());
    }

    private void touchConversation(Claimed m) {
        if (m.contactId() == null || m.test()) return;
        String ch = "MMS".equals(m.channel()) ? "SMS" : m.channel();
        UUID conv = jdbc.queryForObject("""
                INSERT INTO conversations (id, organization_id, contact_id, channel, status, last_message_at) VALUES (?,?,?,?, 'OPEN', now())
                ON CONFLICT (organization_id, contact_id, channel) DO UPDATE SET last_message_at = now(), updated_at = now() RETURNING id""",
                UUID.class, Ids.newId(), m.orgId(), m.contactId(), ch);
        jdbc.update("UPDATE messages SET conversation_id = ? WHERE id = ?", conv, m.id());
    }

    private static String truncate(String s) { return s == null ? null : s.length() > 480 ? s.substring(0, 480) : s; }
}
