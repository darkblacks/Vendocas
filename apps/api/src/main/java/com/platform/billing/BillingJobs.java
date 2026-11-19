package com.platform.billing;

import com.platform.notifications.NotificationService;
import com.platform.shared.AppProperties;
import com.platform.shared.jobs.Job;
import com.platform.shared.jobs.JobHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Dunning policy (configurable constants, documented in docs/billing.md):
 * PAST_DUE for 3 days → GRACE_PERIOD; grace_until (7 days after first failure) passed → SUSPENDED.
 * Open business invoices past their due date move ACTIVE subscriptions to PAST_DUE.
 */
@Component
public class BillingJobs {
    static final Duration PAST_DUE_TO_GRACE = Duration.ofDays(3);
    private final JdbcTemplate jdbc;
    private final SubscriptionRepository subs;
    private final SubscriptionService service;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Clock clock;

    public BillingJobs(JdbcTemplate jdbc, SubscriptionRepository subs, SubscriptionService service, NotificationService notifications,
                       TransactionTemplate tx, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.subs = subs;
        this.service = service;
        this.notifications = notifications;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.dunning-poll-ms:600000}", initialDelay = 30000)
    public void dunning() {
        if (!props.workers().enabled()) return;
        runDunning();
    }

    public void runDunning() {
        var overdue = jdbc.queryForList("""
                SELECT DISTINCT s.id FROM subscriptions s JOIN invoices i ON i.subscription_id = s.id
                WHERE s.status = 'ACTIVE' AND i.status = 'OPEN' AND i.due_at < now()""", UUID.class);
        overdue.forEach(id -> step(id, SubscriptionStatus.PAST_DUE, "Invoice past due date"));
        var toGrace = jdbc.queryForList("SELECT id FROM subscriptions WHERE status = 'PAST_DUE' AND updated_at < ?", UUID.class,
                java.sql.Timestamp.from(clock.instant().minus(PAST_DUE_TO_GRACE)));
        toGrace.forEach(id -> step(id, SubscriptionStatus.GRACE_PERIOD, "Payment still pending; grace period"));
        var toSuspend = jdbc.queryForList("SELECT id FROM subscriptions WHERE status IN ('PAST_DUE','GRACE_PERIOD') AND grace_until < now()", UUID.class);
        toSuspend.forEach(id -> step(id, SubscriptionStatus.SUSPENDED, "Grace period ended without payment"));
    }

    private void step(UUID id, SubscriptionStatus to, String reason) {
        tx.executeWithoutResult(s -> subs.lockById(id).ifPresent(sub -> {
            if (!sub.getStatus().canTransitionTo(to) || sub.getStatus() == to) return;
            service.transition(sub, to, reason, "system:dunning", null);
            String title = to == SubscriptionStatus.SUSPENDED ? "Service suspended" : "Payment pending";
            String body = to == SubscriptionStatus.SUSPENDED
                    ? "Your subscription is suspended for non-payment. Automations and campaigns are paused until payment is confirmed."
                    : "Your subscription payment is pending. Please pay the open invoice to avoid suspension.";
            notifications.notifyBilling(sub.getOrganizationId(), "SUBSCRIPTION_" + to.name(), title, body);
        }));
    }

    @Component
    static class DowngradeHandler implements JobHandler {
        private final SubscriptionService service;

        DowngradeHandler(SubscriptionService service) { this.service = service; }

        @Override public String type() { return "billing.apply_downgrade"; }

        @Override public void handle(Job job) { service.applyPendingDowngrade(UUID.fromString(job.payload().path("subscriptionId").asString())); }
    }
}
