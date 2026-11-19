package com.platform.campaigns;

import com.platform.automation.engine.InstanceFactory;
import com.platform.automation.model.AutomationDefinition;
import com.platform.billing.EntitlementService;
import com.platform.messaging.MessageService;
import com.platform.messaging.SendRequest;
import com.platform.notifications.NotificationService;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.jobs.Job;
import com.platform.shared.jobs.JobHandler;
import com.platform.shared.jobs.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Materializes campaign runs. A tick turns each due schedule into a durable job keyed by
 * (campaign, scheduled time); the run row is unique per (campaign, scheduled_for), recipients are
 * unique per (run, contact) and each message is keyed camp:<run>:<contact>, so restarts or
 * concurrent workers never send a campaign twice.
 */
@Component
public class CampaignRunner implements JobHandler {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final JobService jobs;
    private final MessageService messages;
    private final EntitlementService entitlements;
    private final CampaignService campaigns;
    private final NotificationService notifications;
    private final AppProperties props;
    private final com.platform.billing.CreditWalletService wallet;

    public CampaignRunner(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, JobService jobs, MessageService messages, EntitlementService entitlements,
                          CampaignService campaigns, NotificationService notifications, AppProperties props,
                          com.platform.billing.CreditWalletService wallet) {
        this.jdbc = jdbc;
        this.named = named;
        this.jobs = jobs;
        this.messages = messages;
        this.entitlements = entitlements;
        this.campaigns = campaigns;
        this.notifications = notifications;
        this.props = props;
        this.wallet = wallet;
    }

    @Scheduled(fixedDelayString = "${app.workers.campaign-tick-ms:30000}", initialDelay = 20000)
    public void tick() {
        if (props.workers().enabled()) enqueueDue();
    }

    public void enqueueDue() {
        var due = jdbc.queryForList("""
                SELECT s.campaign_id, s.organization_id, s.next_run_at FROM campaign_schedules s JOIN campaigns c ON c.id = s.campaign_id
                WHERE c.status IN ('SCHEDULED','ACTIVE') AND s.next_run_at IS NOT NULL AND s.next_run_at <= now() + interval '1 minute'""");
        for (var d : due) {
            Instant at = ((Timestamp) d.get("next_run_at")).toInstant();
            jobs.schedule("campaign.run", "camp:" + d.get("campaign_id") + ":" + at.getEpochSecond(), (UUID) d.get("organization_id"),
                    Map.of("campaignId", d.get("campaign_id").toString(), "scheduledFor", at.toString()), at);
        }
    }

    @Override public String type() { return "campaign.run"; }

    @Override
    public void handle(Job job) {
        UUID org = job.organizationId();
        UUID campaignId = UUID.fromString(job.payload().path("campaignId").asString());
        Instant scheduledFor = Instant.parse(job.payload().path("scheduledFor").asString());
        var cRows = jdbc.queryForList("SELECT * FROM campaigns WHERE id = ? AND organization_id = ? FOR UPDATE", campaignId, org);
        if (cRows.isEmpty()) return;
        var c = cRows.getFirst();
        advanceSchedule(org, campaignId, scheduledFor);
        if (!Set.of("SCHEDULED", "ACTIVE").contains((String) c.get("status"))) return;
        UUID runId = Ids.newId();
        int created = jdbc.update("""
                INSERT INTO campaign_runs (id, organization_id, campaign_id, scheduled_for, status, started_at, currency)
                VALUES (?,?,?,?, 'RUNNING', now(), (SELECT currency FROM credit_wallets WHERE organization_id = ?)) ON CONFLICT (campaign_id, scheduled_for) DO NOTHING""",
                runId, org, campaignId, Timestamp.from(scheduledFor), org);
        if (created == 0) return;
        jdbc.update("UPDATE campaigns SET status = 'ACTIVE' WHERE id = ?", campaignId);

        if (!entitlements.of(org).has(EntitlementService.CAMPAIGNS_DAILY)) { block(org, campaignId, runId, "PLAN_OR_SUBSCRIPTION_INACTIVE"); return; }
        long monthlyBudget = ((Number) c.get("monthly_budget_minor")).longValue();
        Long spentMonth = jdbc.queryForObject("""
                SELECT COALESCE(sum(m.cost_reserved_minor),0) FROM messages m JOIN campaign_runs r ON r.id = m.origin_id
                WHERE m.origin_type = 'CAMPAIGN' AND r.campaign_id = ? AND m.status NOT IN ('BLOCKED','CANCELED','FAILED') AND m.created_at >= date_trunc('month', now())""",
                Long.class, campaignId);
        long budget = Math.min(((Number) c.get("budget_per_run_minor")).longValue(), Math.max(0, monthlyBudget - (spentMonth == null ? 0 : spentMonth)));

        AutomationDefinition.Audience a = Json.read(jdbc.queryForObject("SELECT filter::text FROM campaign_audiences WHERE id = ?", String.class, c.get("audience_id")),
                AutomationDefinition.Audience.class);
        String channel = (String) c.get("channel");
        String addr = "EMAIL".equals(channel) ? "c.email::text" : "c.phone_e164";
        MapSqlParameterSource p = new MapSqlParameterSource("org", org).addValue("ch", channel).addValue("lim", c.get("max_recipients_per_run"));
        List<UUID> candidates = named.queryForList("SELECT c.id FROM contacts c WHERE c.organization_id = :org" + InstanceFactory.audienceSql(a, p) + " AND " + addr + """
                 IS NOT NULL AND EXISTS (SELECT 1 FROM contact_consents cc WHERE cc.contact_id = c.id AND cc.channel = :ch AND cc.purpose = 'MARKETING' AND cc.status = 'GRANTED')
                ORDER BY c.id LIMIT :lim""", p, UUID.class);

        long spent = 0;
        int queued = 0, skipped = 0;
        String stopReason = null;
        for (UUID contactId : candidates) {
            if (stopReason != null) { recipient(org, runId, contactId, null, "SKIPPED", stopReason); skipped++; continue; }
            var r = messages.enqueue(new SendRequest(org, contactId, channel, "MARKETING", (UUID) c.get("template_id"), (String) c.get("subject"),
                    body(c), (UUID) c.get("media_asset_id"), "CAMPAIGN", runId, "camp:" + runId + ":" + contactId, false, null,
                    ((Number) c.get("frequency_cap_days")).intValue()));
            if ("QUEUED".equals(r.status())) {
                if (spent + r.costMinor() > budget && r.costMinor() > 0) {
                    jdbc.update("UPDATE messages SET status = 'CANCELED', status_reason = 'CAMPAIGN_BUDGET' WHERE id = ?", r.messageId());
                    releaseReservation(org, r.messageId(), r.costMinor());
                    recipient(org, runId, contactId, r.messageId(), "SKIPPED", "BUDGET");
                    stopReason = "BUDGET";
                    skipped++;
                    continue;
                }
                spent += r.costMinor();
                queued++;
                recipient(org, runId, contactId, r.messageId(), "QUEUED", null);
            } else {
                skipped++;
                recipient(org, runId, contactId, r.messageId(), "SKIPPED", r.reason());
                if (Set.of("INSUFFICIENT_BALANCE", "DAILY_LIMIT", "MONTHLY_LIMIT").contains(r.reason())) stopReason = r.reason();
            }
        }
        jdbc.update("""
                UPDATE campaign_runs SET status = ?, blocked_reason = ?, eligible_count = ?, queued_count = ?, skipped_count = ?, estimated_cost_minor = ?, completed_at = now()
                WHERE id = ?""", queued == 0 && stopReason != null ? "BLOCKED" : "COMPLETED", stopReason, candidates.size(), queued, skipped, spent, runId);
        if (stopReason != null && !"BUDGET".equals(stopReason)) {
            campaigns.pause(org, null, campaignId, "Auto-paused: " + stopReason);
            notifications.notify(org, "WARNING", "CAMPAIGN_PAUSED", "Campaign paused automatically",
                    "\"" + c.get("name") + "\" was paused: " + stopReason + ". Add credits or raise limits, then resume it.", "campaign-paused:" + runId);
        }
    }

    private String body(Map<String, Object> c) {
        String body = (String) c.get("body");
        String link = (String) c.get("link_url");
        if (body == null) return null;
        return link == null || link.isBlank() ? body : body + "\n" + link;
    }

    private void releaseReservation(UUID org, UUID messageId, long amount) {
        wallet.release(org, amount, "msg:" + messageId, "MESSAGE", messageId);
    }

    private void recipient(UUID org, UUID runId, UUID contactId, UUID messageId, String status, String reason) {
        jdbc.update("INSERT INTO campaign_recipients (id, organization_id, run_id, contact_id, message_id, status, skip_reason) VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                Ids.newId(), org, runId, contactId, messageId, status, reason);
    }

    private void block(UUID org, UUID campaignId, UUID runId, String reason) {
        jdbc.update("UPDATE campaign_runs SET status = 'BLOCKED', blocked_reason = ?, completed_at = now() WHERE id = ?", reason, runId);
        campaigns.pause(org, null, campaignId, "Auto-paused: " + reason);
    }

    private void advanceSchedule(UUID org, UUID campaignId, Instant after) {
        var s = campaigns.schedule(org, campaignId);
        Instant next = "ONCE".equals(s.kind()) ? null : CampaignService.nextRun(s, after);
        jdbc.update("UPDATE campaign_schedules SET last_run_at = ?, next_run_at = ? WHERE campaign_id = ?", Timestamp.from(after),
                next == null ? null : Timestamp.from(next), campaignId);
        if (next == null) jdbc.update("UPDATE campaigns SET status = 'COMPLETED' WHERE id = ? AND status IN ('SCHEDULED','ACTIVE')", campaignId);
    }

}
