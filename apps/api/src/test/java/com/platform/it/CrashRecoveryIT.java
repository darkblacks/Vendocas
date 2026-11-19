package com.platform.it;

import com.platform.automation.AutomationService;
import com.platform.automation.engine.AutomationWorker;
import com.platform.automation.model.AutomationDefinition;
import com.platform.messaging.MessageDispatcher;
import com.platform.shared.Json;
import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import com.platform.support.TestProviders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance test I: thousands of pending automations survive a crash (expired leases) and concurrent
 * workers, with no lost instance and no duplicated message.
 */
class CrashRecoveryIT extends IntegrationTest {
    static final int N = 2000;
    @Autowired Fixtures fx;
    @Autowired AutomationService automations;
    @Autowired AutomationWorker worker;
    @Autowired MessageDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;

    @Test
    void noLossNoDuplicatesAfterCrashWithConcurrentWorkers() throws Exception {
        TestProviders.nextOutcome = com.platform.messaging.providers.MessageProvider.Outcome.ACCEPTED;
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "EMAIL_STARTER");
        fx.emailChannel(t.orgId());
        jdbc.update("UPDATE organization_settings SET quiet_hours_start = '00:00', quiet_hours_end = '00:00', default_frequency_cap = 50 WHERE organization_id = ?", t.orgId());
        AutomationDefinition def = Json.read("""
                {"trigger":{"type":"CONTACT_CREATED"},
                 "steps":[{"id":"send","type":"SEND","channel":"EMAIL","purpose":"MARKETING","subject":"Hello","body":"Welcome {{contact.first_name}}"},
                          {"id":"tag","type":"ADD_TAG","tag":"welcomed"}]}""", AutomationDefinition.class);
        UUID automation = automations.create(t.orgId(), t.userId(), "Welcome", null, "SIMPLE", def);
        automations.activate(t.orgId(), t.userId(), automation, null);
        UUID version = jdbc.queryForObject("SELECT active_version_id FROM automation_definitions WHERE id = ?", UUID.class, automation);

        // Bulk: N contacts with consent and N READY instances.
        jdbc.update("""
                INSERT INTO contacts (id, organization_id, name, email, country_code, status, source)
                SELECT gen_random_uuid(), ?, 'Bulk ' || g, 'bulk' || g || '-' || ? || '@example.com', 'BR', 'ACTIVE', 'IMPORT' FROM generate_series(1, ?) g""",
                t.orgId(), t.orgId().toString().substring(0, 8), N);
        jdbc.update("""
                INSERT INTO contact_consents (id, organization_id, contact_id, channel, purpose, status, legal_basis, granted_at)
                SELECT gen_random_uuid(), organization_id, id, 'EMAIL', 'MARKETING', 'GRANTED', 'CONSENT', now() FROM contacts WHERE organization_id = ? AND source = 'IMPORT'""",
                t.orgId());
        jdbc.update("""
                INSERT INTO automation_instances (id, organization_id, automation_id, version_id, contact_id, source_event_type, idempotency_key, state, current_step_id, next_run_at, context)
                SELECT gen_random_uuid(), ?, ?, ?, id, 'contact.created', 'EVT:' || id, 'READY', 'send', now(), '{}'::jsonb FROM contacts WHERE organization_id = ? AND source = 'IMPORT'""",
                t.orgId(), automation, version, t.orgId());

        // Simulated crash: a worker had claimed 500 instances and died (lease expired, nothing committed).
        jdbc.update("""
                UPDATE automation_instances SET state = 'PROCESSING', lease_until = now() - interval '1 minute', leased_by = 'dead-worker'
                WHERE id IN (SELECT id FROM automation_instances WHERE automation_id = ? LIMIT 500)""", automation);

        // Restart: 4 concurrent workers + dispatchers drain everything.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int w = 0; w < 4; w++) {
            pool.submit(() -> {
                for (int i = 0; i < 40; i++) {
                    int a = worker.runOnce(100);
                    int b = dispatcher.dispatchBatch(100);
                    if (a == 0 && b == 0 && i > 5) break;
                }
                return null;
            });
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.MINUTES)).isTrue();
        while (worker.runOnce(500) > 0 || dispatcher.dispatchBatch(500) > 0) { /* drain */ }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM automation_instances WHERE automation_id = ? AND state = 'COMPLETED'", Integer.class, automation)).isEqualTo(N);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE organization_id = ? AND origin_type = 'AUTOMATION'", Integer.class, t.orgId())).isEqualTo(N);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT contact_id) FROM messages WHERE organization_id = ? AND status = 'SUBMITTED'", Integer.class, t.orgId())).isEqualTo(N);
        long providerCalls = TestProviders.SENT.stream().filter(m -> m.to().contains("-" + t.orgId().toString().substring(0, 8) + "@")).count();
        assertThat(providerCalls).isEqualTo(N);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_tags ct JOIN tags tg ON tg.id = ct.tag_id WHERE tg.organization_id = ? AND tg.name = 'welcomed'", Integer.class, t.orgId())).isEqualTo(N);
    }

    @Test
    void messageWithUnknownProviderOutcomeIsFailedNotResent() {
        var t = fx.brazil();
        UUID c = fx.contact(t.orgId(), "unknown-" + UUID.randomUUID() + "@example.com", null, true);
        UUID m = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO messages (id, organization_id, contact_id, direction, channel, purpose, status, to_address, provider, idempotency_key, updated_at)
                VALUES (?,?,?, 'OUTBOUND', 'EMAIL', 'MARKETING', 'SUBMITTED', 'x@example.com', 'FAKE', ?, now() - interval '10 minutes')""", m, t.orgId(), c, "k:" + m);
        dispatcher.recoverUnknownOutcomes();
        assertThat(jdbc.queryForObject("SELECT status || ':' || status_reason FROM messages WHERE id = ?", String.class, m)).isEqualTo("FAILED:UNKNOWN_OUTCOME_NOT_RETRIED");
    }
}
