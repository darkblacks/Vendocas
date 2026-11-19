package com.platform.automation.engine;

import com.platform.automation.model.AutomationDefinition;
import com.platform.automation.model.AutomationDefinition.Step;
import com.platform.automation.model.DefinitionValidator;
import com.platform.automation.model.TimeRules;
import com.platform.contacts.Contact;
import com.platform.contacts.ContactRepository;
import com.platform.messaging.MessageService;
import com.platform.messaging.SendRequest;
import com.platform.notifications.NotificationService;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Executes automation instances. Durable and restart-safe:
 * <ul>
 *   <li>Due instances are claimed with FOR UPDATE SKIP LOCKED and a lease; an instance whose lease
 *       expired (crashed worker) is reclaimed.</li>
 *   <li>Each instance advances in ONE transaction: step actions, execution log, message enqueue and the
 *       instance's new position commit together or not at all, so a crash re-runs the step cleanly.</li>
 *   <li>Every step checks for a prior EXECUTED log entry and every send uses the idempotency key
 *       {@code auto:<instance>:<step>}, so a re-run never duplicates an action.</li>
 *   <li>Instances of PAUSED automations are not claimed; they resume when the automation is resumed,
 *       subject to the late-execution tolerance.</li>
 * </ul>
 */
@Component
public class AutomationWorker {
    static final int MAX_STEPS_PER_RUN = 25;
    private static final Logger log = LoggerFactory.getLogger(AutomationWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final VersionCache versions;
    private final ContactRepository contacts;
    private final ConditionEvaluator conditions;
    private final MessageService messages;
    private final NotificationService notifications;
    private final WebhookCaller webhooks;
    private final AppProperties props;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public AutomationWorker(JdbcTemplate jdbc, TransactionTemplate tx, VersionCache versions, ContactRepository contacts,
                            ConditionEvaluator conditions, MessageService messages, NotificationService notifications, WebhookCaller webhooks,
                            AppProperties props, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.versions = versions;
        this.contacts = contacts;
        this.conditions = conditions;
        this.messages = messages;
        this.notifications = notifications;
        this.webhooks = webhooks;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    record Claimed(UUID id, UUID orgId, UUID automationId, UUID versionId, UUID contactId, String stepId, Instant nextRunAt, int attempts,
                   JsonNode context) {}

    @Scheduled(fixedDelayString = "${app.workers.automation-poll-ms:1000}")
    public void poll() {
        if (!props.workers().enabled()) return;
        runOnce(props.workers().batchSize());
    }

    public int runOnce(int limit) {
        long lease = props.workers().leaseDuration().toSeconds();
        List<Claimed> claimed = tx.execute(s -> jdbc.query("""
                UPDATE automation_instances i SET state = 'PROCESSING', lease_until = now() + make_interval(secs => ?), leased_by = ?,
                       attempts = i.attempts + 1, updated_at = now()
                WHERE i.id IN (SELECT x.id FROM automation_instances x JOIN automation_definitions d ON d.id = x.automation_id
                               WHERE d.status = 'ACTIVE' AND ((x.state IN ('PENDING','WAITING','READY') AND x.next_run_at <= now())
                                  OR (x.state = 'PROCESSING' AND x.lease_until < now()))
                               ORDER BY x.next_run_at LIMIT ? FOR UPDATE OF x SKIP LOCKED)
                RETURNING i.id, i.organization_id, i.automation_id, i.version_id, i.contact_id, i.current_step_id, i.next_run_at, i.attempts, i.context::text""",
                (rs, n) -> new Claimed(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getString(6), rs.getTimestamp(7) == null ? clock.instant() : rs.getTimestamp(7).toInstant(),
                        rs.getInt(8), Json.read(rs.getString(9))), lease, workerId, limit));
        if (claimed == null) return 0;
        for (Claimed c : claimed) {
            try {
                tx.executeWithoutResult(s -> advance(c));
                meters.counter("automation.instances.advanced").increment();
            } catch (RuntimeException e) {
                log.warn("Automation instance {} failed at step {}: {}", c.id(), c.stepId(), e.toString());
                fail(c, e);
            }
        }
        return claimed.size();
    }

    private void advance(Claimed c) {
        AutomationDefinition def = versions.get(c.versionId());
        Map<String, Step> steps = new HashMap<>();
        def.steps().forEach(s -> steps.put(s.id(), s));
        ZoneId orgZone = ZoneId.of(jdbc.queryForObject("SELECT time_zone FROM organizations WHERE id = ?", String.class, c.orgId()));
        Contact contact = c.contactId() == null ? null : contacts.find(c.orgId(), c.contactId()).orElse(null);
        if (contact == null || !"ACTIVE".equals(contact.status())) {
            finish(c.id(), "CANCELED", "Contact no longer active", null);
            return;
        }
        Instant now = clock.instant();
        Instant triggerAt = c.context().has("triggerAt") ? parseInstant(c.context().path("triggerAt").asString(), now) : now;
        var settings = def.settings() == null ? new AutomationDefinition.Settings(null, null, null, null, null, null, null) : def.settings();
        String exitHit = exitEventSince(contact.id(), settings.exits(), triggerAt);
        if (exitHit != null) {
            finish(c.id(), "CANCELED", "Exit event: " + exitHit, null);
            return;
        }
        var ctx = new ConditionEvaluator.Ctx(c.orgId(), contact, triggerAt, now, orgZone, c.automationId());
        String stepId = c.stepId();
        for (int i = 0; i < MAX_STEPS_PER_RUN && stepId != null; i++) {
            Step step = steps.get(stepId);
            if (step == null) { finish(c.id(), "FAILED", "Unknown step " + stepId, null); return; }
            boolean alreadyDone = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM automation_executions WHERE instance_id = ? AND step_id = ? AND outcome IN ('EXECUTED','PASSED','FAILED_CONDITION','WAITING'))""",
                    Boolean.class, c.id(), stepId));
            switch (step.type()) {
                case WAIT -> {
                    if (!alreadyDone) {
                        Instant until = TimeRules.add(now, step.duration(), zoneFor(step, contact, orgZone));
                        log(c, step, "WAITING", "Until " + until, null);
                        park(c.id(), DefinitionValidator.nextOf(def, step), until);
                        return;
                    }
                    stepId = DefinitionValidator.nextOf(def, step);
                }
                case WAIT_UNTIL -> {
                    if (!alreadyDone) {
                        Instant until = TimeRules.nextLocalTime(now, LocalTime.parse(step.time()), zoneFor(step, contact, orgZone),
                                Boolean.TRUE.equals(step.businessDaysOnly()));
                        log(c, step, "WAITING", "Until " + until, null);
                        park(c.id(), DefinitionValidator.nextOf(def, step), until);
                        return;
                    }
                    stepId = DefinitionValidator.nextOf(def, step);
                }
                case CONDITION -> {
                    boolean all = !"ANY".equals(step.match());
                    boolean result = all ? step.conditions().stream().allMatch(x -> conditions.eval(x, ctx))
                            : step.conditions().stream().anyMatch(x -> conditions.eval(x, ctx));
                    log(c, step, result ? "PASSED" : "FAILED_CONDITION", null, null);
                    String target = result ? step.onTrue() : step.onFalse();
                    if (target == null || "end".equals(target)) {
                        finish(c.id(), result ? "COMPLETED" : "SKIPPED", result ? "Completed" : "Conditions not met at " + step.id(), null);
                        return;
                    }
                    stepId = target;
                }
                case SEND -> {
                    if (!alreadyDone) {
                        Duration late = Duration.between(c.nextRunAt(), now);
                        if (late.toHours() >= settings.lateTolerance()) {
                            log(c, step, "SKIPPED", "Late by " + late.toMinutes() + " min (tolerance " + settings.lateTolerance() + "h)", null);
                            finish(c.id(), "SKIPPED", null, "LATE_EXECUTION_WINDOW_EXCEEDED");
                            return;
                        }
                        String budget = budgetCheck(c, settings);
                        MessageService.EnqueueResult res = budget != null
                                ? new MessageService.EnqueueResult(null, "BLOCKED", budget, 0, false)
                                : messages.enqueue(new SendRequest(c.orgId(), contact.id(), step.channel(), step.purpose(), step.templateId(),
                                        step.subject(), step.body(), step.mediaAssetId(), "AUTOMATION", c.id(), "auto:" + c.id() + ":" + step.id(),
                                        false, null, null));
                        if ("BLOCKED".equals(res.status())) {
                            log(c, step, "BLOCKED", res.reason(), res.messageId());
                            if (Set.of("INSUFFICIENT_BALANCE", "DAILY_LIMIT", "MONTHLY_LIMIT", "AUTOMATION_BUDGET_EXCEEDED").contains(res.reason())) {
                                notifications.notify(c.orgId(), "WARNING", "AUTOMATION_BUDGET", "Messages blocked by budget",
                                        "An automation could not send because of " + res.reason() + ". Top up credits or raise the limit.",
                                        "budget:" + c.automationId() + ":" + LocalDate.now(orgZone));
                            }
                            if (!settings.continueOnBlocked()) {
                                finish(c.id(), "SKIPPED", null, res.reason());
                                return;
                            }
                        } else {
                            log(c, step, "EXECUTED", res.status(), res.messageId());
                        }
                    }
                    stepId = DefinitionValidator.nextOf(def, step);
                }
                case END -> { finish(c.id(), "COMPLETED", "Completed", null); return; }
                default -> {
                    if (!alreadyDone) {
                        String detail = action(c, step, contact, orgZone);
                        log(c, step, "EXECUTED", detail, null);
                    }
                    stepId = DefinitionValidator.nextOf(def, step);
                }
            }
            jdbc.update("UPDATE automation_instances SET current_step_id = ? WHERE id = ?", stepId, c.id());
        }
        if (stepId == null) finish(c.id(), "COMPLETED", "Completed", null);
        else park(c.id(), stepId, now); // step budget exhausted: continue on next poll
    }

    private String action(Claimed c, Step s, Contact contact, ZoneId zone) {
        switch (s.type()) {
            case ADD_TAG -> {
                jdbc.update("INSERT INTO tags (id, organization_id, name) VALUES (?,?,?) ON CONFLICT (organization_id, name) DO NOTHING", Ids.newId(), c.orgId(), s.tag());
                jdbc.update("INSERT INTO contact_tags (contact_id, tag_id) SELECT ?, id FROM tags WHERE organization_id = ? AND name = ? ON CONFLICT DO NOTHING",
                        contact.id(), c.orgId(), s.tag());
                return "Tag added: " + s.tag();
            }
            case REMOVE_TAG -> {
                jdbc.update("DELETE FROM contact_tags WHERE contact_id = ? AND tag_id IN (SELECT id FROM tags WHERE organization_id = ? AND name = ?)",
                        contact.id(), c.orgId(), s.tag());
                return "Tag removed: " + s.tag();
            }
            case SET_CONTACT_STATUS -> {
                jdbc.update("UPDATE contacts SET status = ?, updated_at = now() WHERE id = ? AND organization_id = ?", s.status(), contact.id(), c.orgId());
                return "Status " + s.status();
            }
            case CREATE_TASK -> {
                jdbc.update("""
                        INSERT INTO callback_tasks (id, organization_id, contact_id, phone_e164, source_type, source_id, status, due_at, notes)
                        VALUES (?,?,?,?, 'AUTOMATION', ?, 'OPEN', ?, ?)""", Ids.newId(), c.orgId(), contact.id(), contact.phoneE164(), c.id(),
                        Timestamp.from(clock.instant().plus(Duration.ofHours(s.dueInHours() == null ? 24 : s.dueInHours()))), s.title());
                return "Task created";
            }
            case CREATE_OPPORTUNITY -> {
                jdbc.update("""
                        INSERT INTO opportunities (id, organization_id, contact_id, title, status, source_type, source_id, value_minor, currency)
                        VALUES (?,?,?,?, 'OPEN', 'AUTOMATION', ?, ?, (SELECT billing_currency FROM organizations WHERE id = ?))""",
                        Ids.newId(), c.orgId(), contact.id(), s.title(), c.id(), s.valueMinor(), c.orgId());
                return "Opportunity created";
            }
            case NOTIFY_OPERATOR -> {
                notifications.notify(c.orgId(), "INFO", "AUTOMATION_NOTICE", s.title() == null ? "Automation notice" : s.title(),
                        s.message() + (contact.name() == null ? "" : " — " + contact.name()), "auto-notify:" + c.id() + ":" + s.id());
                return "Operator notified";
            }
            case CALL_WEBHOOK -> {
                return webhooks.call(c.orgId(), s.url(), "auto:" + c.id() + ":" + s.id(), Map.of("automationId", c.automationId().toString(),
                        "instanceId", c.id().toString(), "contactId", contact.id().toString(), "stepId", s.id()));
            }
            default -> throw new IllegalStateException("Unsupported action " + s.type());
        }
    }

    private String budgetCheck(Claimed c, AutomationDefinition.Settings settings) {
        if (settings.monthlyBudgetMinor() == null) return null;
        Long spent = jdbc.queryForObject("""
                SELECT COALESCE(sum(m.cost_reserved_minor), 0) FROM messages m JOIN automation_instances i ON i.id = m.origin_id
                WHERE m.origin_type = 'AUTOMATION' AND i.automation_id = ? AND m.status NOT IN ('BLOCKED','CANCELED','FAILED')
                  AND m.created_at >= date_trunc('month', now())""", Long.class, c.automationId());
        return spent != null && spent >= settings.monthlyBudgetMinor() ? "AUTOMATION_BUDGET_EXCEEDED" : null;
    }

    private String exitEventSince(UUID contactId, List<String> exits, Instant since) {
        if (exits.isEmpty()) return null;
        var hit = jdbc.queryForList("SELECT type FROM contact_events WHERE contact_id = ? AND type = ANY(?) AND occurred_at > ? LIMIT 1", String.class,
                contactId, exits.toArray(String[]::new), Timestamp.from(since));
        return hit.isEmpty() ? null : hit.getFirst();
    }

    private ZoneId zoneFor(Step s, Contact c, ZoneId orgZone) {
        return "CONTACT".equals(s.zone()) && c.timeZone() != null ? ZoneId.of(c.timeZone()) : orgZone;
    }

    private void log(Claimed c, Step s, String outcome, String detail, UUID messageId) {
        jdbc.update("""
                INSERT INTO automation_executions (id, organization_id, instance_id, step_id, step_type, outcome, detail, message_id, attempt_no)
                VALUES (?,?,?,?,?,?,?,?,?)""", Ids.newId(), c.orgId(), c.id(), s.id(), s.type().name(), outcome,
                detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail, messageId, c.attempts());
    }

    private void park(UUID id, String nextStep, Instant until) {
        if (nextStep == null) { finish(id, "COMPLETED", "Completed", null); return; }
        jdbc.update("""
                UPDATE automation_instances SET state = 'WAITING', current_step_id = ?, next_run_at = ?, lease_until = NULL, leased_by = NULL,
                  attempts = 0, updated_at = now() WHERE id = ?""", nextStep, Timestamp.from(until), id);
    }

    private void finish(UUID id, String state, String result, String blocked) {
        jdbc.update("""
                UPDATE automation_instances SET state = ?, result = ?, blocked_reason = ?, completed_at = now(), lease_until = NULL, next_run_at = NULL,
                  updated_at = now() WHERE id = ?""", state, result, blocked, id);
    }

    private void fail(Claimed c, RuntimeException e) {
        AutomationDefinition def = versions.get(c.versionId());
        int max = def.settings() == null ? 5 : def.settings().maxAttempts();
        boolean dead = c.attempts() >= max;
        String msg = e.toString().length() > 300 ? e.toString().substring(0, 300) : e.toString();
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE automation_instances SET state = ?, result = ?, next_run_at = CASE WHEN ? THEN NULL ELSE now() + make_interval(secs => ?) END,
                  lease_until = NULL, updated_at = now(), completed_at = CASE WHEN ? THEN now() END WHERE id = ?""",
                dead ? "DEAD_LETTER" : "READY", msg, dead, (int) Math.min(3600, Math.pow(2, c.attempts()) * 30), dead, c.id()));
        meters.counter(dead ? "automation.instances.dead" : "automation.instances.retry").increment();
    }

    private static Instant parseInstant(String s, Instant fallback) {
        try { return Instant.parse(s); } catch (Exception e) {
            try { return OffsetDateTime.parse(s.replace(' ', 'T')).toInstant(); } catch (Exception e2) { return fallback; }
        }
    }
}
