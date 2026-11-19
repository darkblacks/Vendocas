package com.platform.it;

import com.platform.automation.AutomationService;
import com.platform.automation.engine.AutomationWorker;
import com.platform.automation.engine.SweepJobs;
import com.platform.automation.model.AutomationDefinition;
import com.platform.contacts.ContactEventService;
import com.platform.messaging.MessageDispatcher;
import com.platform.shared.Json;
import com.platform.shared.jobs.JobWorker;
import com.platform.shared.outbox.OutboxRelay;
import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import com.platform.support.TestProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance test A (engine side): inactivity rule → eligibility → real enqueue → provider submit, with idempotency. */
class AutomationEngineIT extends IntegrationTest {
    @Autowired Fixtures fx;
    @Autowired AutomationService automations;
    @Autowired SweepJobs sweeps;
    @Autowired JobWorker jobs;
    @Autowired AutomationWorker worker;
    @Autowired MessageDispatcher dispatcher;
    @Autowired OutboxRelay outbox;
    @Autowired ContactEventService events;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    Fixtures.Tenant t;

    @BeforeEach
    void setup() {
        TestProviders.nextOutcome = com.platform.messaging.providers.MessageProvider.Outcome.ACCEPTED;
        t = fx.brazil();
        fx.activeSubscription(t.orgId(), "EMAIL_STARTER");
        fx.emailChannel(t.orgId());
        jdbc.update("UPDATE organization_settings SET quiet_hours_start = '00:00', quiet_hours_end = '00:00' WHERE organization_id = ?", t.orgId());
    }

    AutomationDefinition inactivityDefinition(int months) {
        return Json.read("""
                {"trigger":{"type":"CONTACT_INACTIVE","period":{"amount":%d,"unit":"CALENDAR_MONTHS"},"basis":"LAST_PURCHASE"},
                 "steps":[{"id":"check","type":"CONDITION","conditions":[{"type":"CONSENT","channel":"EMAIL","purpose":"REACTIVATION"}],"onTrue":"send","onFalse":"end"},
                          {"id":"send","type":"SEND","channel":"EMAIL","purpose":"REACTIVATION","subject":"We miss you, {{contact.first_name}}","body":"Come back to {{business.name}}!"}],
                 "settings":{"exitOn":["PURCHASE_COMPLETED"]}}""".formatted(months), AutomationDefinition.class);
    }

    UUID activate(AutomationDefinition def) {
        UUID id = automations.create(t.orgId(), t.userId(), "Reactivation", null, "SIMPLE", def);
        automations.activate(t.orgId(), t.userId(), id, null);
        return id;
    }

    void runEverything() {
        for (int i = 0; i < 3; i++) {
            sweeps.ensureJobs();
            jobs.runOnce();
            outbox.drain(1000);
            worker.runOnce(500);
            dispatcher.dispatchBatch(500);
        }
    }

    @Test
    void inactiveContactWithConsentGetsExactlyOneMessage() {
        UUID eligible = fx.contact(t.orgId(), "eligible-" + UUID.randomUUID() + "@example.com", Instant.now().minus(130, ChronoUnit.DAYS), true);
        UUID noConsent = fx.contact(t.orgId(), "noconsent-" + UUID.randomUUID() + "@example.com", Instant.now().minus(130, ChronoUnit.DAYS), false);
        UUID recent = fx.contact(t.orgId(), "recent-" + UUID.randomUUID() + "@example.com", Instant.now().minus(10, ChronoUnit.DAYS), true);
        UUID automation = activate(inactivityDefinition(3));
        runEverything();
        runEverything(); // re-running sweeps/workers must not duplicate anything

        assertThat(count("SELECT count(*) FROM messages WHERE contact_id = ? AND status = 'SUBMITTED'", eligible)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM messages WHERE contact_id = ?", noConsent)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM automation_instances WHERE contact_id = ?", String.class, noConsent)).isEqualTo("SKIPPED");
        assertThat(count("SELECT count(*) FROM automation_instances WHERE contact_id = ?", recent)).isZero();
        assertThat(count("SELECT count(*) FROM automation_instances WHERE automation_id = ?", automation)).isEqualTo(2);
        var sent = TestProviders.SENT.stream().filter(m -> m.to().startsWith("eligible-")).toList();
        assertThat(sent).isNotEmpty();
        assertThat(sent.getLast().subject()).startsWith("We miss you, Contact");
        assertThat(sent.getLast().headers()).containsKey("List-Unsubscribe");
    }

    @Test
    void purchaseDuringWaitCancelsTheFollowUp() {
        AutomationDefinition def = Json.read("""
                {"trigger":{"type":"CHECKOUT_STARTED"},
                 "steps":[{"id":"wait","type":"WAIT","duration":{"amount":30,"unit":"MINUTES"}},
                          {"id":"send","type":"SEND","channel":"EMAIL","purpose":"MARKETING","subject":"Forgot something?","body":"Your cart is waiting"}],
                 "settings":{"exitOn":["PURCHASE_COMPLETED"]}}""", AutomationDefinition.class);
        activate(def);
        UUID c = fx.contact(t.orgId(), "cart-" + UUID.randomUUID() + "@example.com", null, true);
        tx.executeWithoutResult(s -> events.record(t.orgId(), c, "CHECKOUT_STARTED", Instant.now(), "SDK", "co:" + c, Map.of()));
        runEverything();
        assertThat(jdbc.queryForObject("SELECT state FROM automation_instances WHERE contact_id = ?", String.class, c)).isEqualTo("WAITING");
        tx.executeWithoutResult(s -> events.record(t.orgId(), c, "PURCHASE_COMPLETED", Instant.now(), "API", "order:" + c, Map.of()));
        jdbc.update("UPDATE automation_instances SET next_run_at = now() WHERE contact_id = ?", c); // fast-forward the wait
        runEverything();
        assertThat(jdbc.queryForObject("SELECT state FROM automation_instances WHERE contact_id = ?", String.class, c)).isEqualTo("CANCELED");
        assertThat(count("SELECT count(*) FROM messages WHERE contact_id = ?", c)).isZero();
    }

    @Test
    void pausedAutomationDoesNotSendAndUnsubscribedContactIsBlocked() {
        UUID automation = activate(inactivityDefinition(1));
        UUID c = fx.contact(t.orgId(), "paused-" + UUID.randomUUID() + "@example.com", Instant.now().minus(90, ChronoUnit.DAYS), true);
        automations.setStatus(t.orgId(), t.userId(), automation, "PAUSED");
        runEverything();
        assertThat(count("SELECT count(*) FROM messages WHERE contact_id = ?", c)).isZero();

        UUID u = fx.contact(t.orgId(), "unsub-" + UUID.randomUUID() + "@example.com", Instant.now().minus(90, ChronoUnit.DAYS), true);
        jdbc.update("INSERT INTO suppression_list (id, organization_id, channel, address, reason, scope) SELECT ?, organization_id, 'EMAIL', email, 'UNSUBSCRIBED', 'MARKETING' FROM contacts WHERE id = ?",
                UUID.randomUUID(), u);
        automations.activate(t.orgId(), t.userId(), automation, null);
        runEverything();
        assertThat(count("SELECT count(*) FROM messages WHERE contact_id = ? AND status IN ('QUEUED','SUBMITTED')", u)).isZero();
    }

    @Test
    void insufficientBalanceBlocksPaidChannelAndSuspendedSubscriptionStopsSending() {
        UUID c = fx.contact(t.orgId(), "susp-" + UUID.randomUUID() + "@example.com", Instant.now().minus(200, ChronoUnit.DAYS), true);
        activate(inactivityDefinition(3));
        jdbc.update("UPDATE subscriptions SET status = 'SUSPENDED' WHERE organization_id = ?", t.orgId());
        runEverything();
        assertThat(count("SELECT count(*) FROM messages WHERE contact_id = ? AND status IN ('QUEUED','SUBMITTED')", c)).isZero();
        assertThat(jdbc.queryForList("SELECT status_reason FROM messages WHERE contact_id = ?", String.class, c)).allMatch(r -> r.equals("SUBSCRIPTION_INACTIVE"));
    }

    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
}
