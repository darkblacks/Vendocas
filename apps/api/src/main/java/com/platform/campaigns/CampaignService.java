package com.platform.campaigns;

import com.platform.audit.AuditService;
import com.platform.automation.engine.InstanceFactory;
import com.platform.automation.model.AutomationDefinition;
import com.platform.automation.model.TimeRules;
import com.platform.billing.EntitlementService;
import com.platform.billing.UsageRates;
import com.platform.channels.ChannelService;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Recurring promotional campaigns (Daily Campaigns plan). Not unlimited: each run is capped by
 * max recipients, per-run budget, monthly budget, wallet balance/spend limits, per-recipient
 * frequency cap, consent (MARKETING) and opt-outs — all enforced again per message by MessageService.
 */
@Service
public class CampaignService {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final EntitlementService entitlements;
    private final ChannelService channels;
    private final UsageRates rates;
    private final AuditService audit;

    public CampaignService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, EntitlementService entitlements, ChannelService channels,
                           UsageRates rates, AuditService audit) {
        this.jdbc = jdbc;
        this.named = named;
        this.entitlements = entitlements;
        this.channels = channels;
        this.rates = rates;
        this.audit = audit;
    }

    public record ScheduleInput(@NotBlank @Pattern(regexp = "ONCE|DAILY|WEEKLY|CUSTOM_DAYS") String kind, List<@Min(1) @Max(7) Integer> daysOfWeek,
                                @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String localTime, @NotBlank String timeZone, @NotNull LocalDate startDate,
                                LocalDate endDate) {}

    public record CampaignInput(@NotBlank @Size(max = 150) String name, @NotBlank @Pattern(regexp = "EMAIL|WHATSAPP|SMS") String channel,
                                UUID templateId, @Size(max = 300) String subject, @Size(max = 4000) String body, UUID mediaAssetId,
                                @Size(max = 1000) String linkUrl, AutomationDefinition.Audience audience,
                                @Min(1) @Max(50000) int maxRecipientsPerRun, @Min(0) long budgetPerRunMinor, @Min(0) long monthlyBudgetMinor,
                                @Min(1) @Max(90) int frequencyCapDays, @NotNull ScheduleInput schedule) {}

    public record CampaignView(UUID id, String name, String status, String pauseReason, String channel, UUID templateId, String subject, String body,
                               UUID mediaAssetId, String linkUrl, AutomationDefinition.Audience audience, int maxRecipientsPerRun, long budgetPerRunMinor,
                               long monthlyBudgetMinor, int frequencyCapDays, ScheduleInput schedule, Instant nextRunAt, Instant updatedAt) {}

    @Transactional
    public UUID create(UUID org, UUID userId, CampaignInput in) {
        entitlements.require(org, EntitlementService.CAMPAIGNS_DAILY);
        validate(org, in);
        UUID id = Ids.newId();
        UUID audienceId = Ids.newId();
        jdbc.update("INSERT INTO campaign_audiences (id, organization_id, name, filter) VALUES (?,?,?,?::jsonb)", audienceId, org, in.name(),
                Json.write(in.audience() == null ? Map.of() : in.audience()));
        jdbc.update("""
                INSERT INTO campaigns (id, organization_id, name, status, channel, purpose, template_id, subject, body, media_asset_id, link_url, audience_id,
                  max_recipients_per_run, budget_per_run_minor, monthly_budget_minor, frequency_cap_days, created_by)
                VALUES (?,?,?, 'DRAFT', ?, 'MARKETING', ?,?,?,?,?,?,?,?,?,?,?)""", id, org, in.name(), in.channel(), in.templateId(), in.subject(), in.body(),
                in.mediaAssetId(), in.linkUrl(), audienceId, in.maxRecipientsPerRun(), in.budgetPerRunMinor(), in.monthlyBudgetMinor(), in.frequencyCapDays(), userId);
        upsertSchedule(org, id, in.schedule(), false);
        audit.user(org, userId, "campaign.created", "campaign", id, Map.of("name", in.name()));
        return id;
    }

    @Transactional
    public void update(UUID org, UUID userId, UUID id, CampaignInput in) {
        var c = load(org, id);
        if ("ACTIVE".equals(c.get("status")) || "SCHEDULED".equals(c.get("status"))) throw ApiException.invalidState("Pause the campaign before editing it");
        validate(org, in);
        jdbc.update("UPDATE campaign_audiences SET filter = ?::jsonb, updated_at = now() WHERE id = ?", Json.write(in.audience() == null ? Map.of() : in.audience()), c.get("audience_id"));
        jdbc.update("""
                UPDATE campaigns SET name=?, channel=?, template_id=?, subject=?, body=?, media_asset_id=?, link_url=?, max_recipients_per_run=?,
                  budget_per_run_minor=?, monthly_budget_minor=?, frequency_cap_days=?, updated_at=now(), version=version+1 WHERE id=? AND organization_id=?""",
                in.name(), in.channel(), in.templateId(), in.subject(), in.body(), in.mediaAssetId(), in.linkUrl(), in.maxRecipientsPerRun(),
                in.budgetPerRunMinor(), in.monthlyBudgetMinor(), in.frequencyCapDays(), id, org);
        upsertSchedule(org, id, in.schedule(), false);
        audit.user(org, userId, "campaign.updated", "campaign", id, Map.of());
    }

    @Transactional
    public void activate(UUID org, UUID userId, UUID id) {
        entitlements.require(org, EntitlementService.CAMPAIGNS_DAILY);
        var c = load(org, id);
        String channel = (String) c.get("channel");
        if (channels.sendable(org, channel).isEmpty()) throw ApiException.invalidState("Connect " + channel + " before activating the campaign");
        if ("WHATSAPP".equals(channel)) {
            String st = jdbc.queryForList("SELECT status FROM message_templates WHERE id = ?", String.class, c.get("template_id")).stream().findFirst().orElse("MISSING");
            if (!"APPROVED".equals(st)) throw ApiException.invalidState("WhatsApp campaigns need an approved MARKETING template (current: " + st + ")");
        }
        var s = schedule(org, id);
        upsertSchedule(org, id, s, true);
        jdbc.update("UPDATE campaigns SET status = 'SCHEDULED', pause_reason = NULL, updated_at = now() WHERE id = ?", id);
        audit.user(org, userId, "campaign.activated", "campaign", id, Map.of());
    }

    @Transactional
    public void pause(UUID org, UUID userId, UUID id, String reason) {
        load(org, id);
        jdbc.update("UPDATE campaigns SET status = 'PAUSED', pause_reason = ?, updated_at = now() WHERE id = ? AND organization_id = ?", reason, id, org);
        jdbc.update("UPDATE campaign_schedules SET next_run_at = NULL WHERE campaign_id = ?", id);
        // Messages already queued for this campaign are canceled by the dispatcher's send-time re-check.
        if (userId != null) audit.user(org, userId, "campaign.paused", "campaign", id, Map.of());
    }

    public List<CampaignView> list(UUID org) {
        return jdbc.query("""
                SELECT c.*, a.filter::text AS audience, s.kind, s.days_of_week, s.local_time, s.time_zone, s.start_date, s.end_date, s.next_run_at
                FROM campaigns c LEFT JOIN campaign_audiences a ON a.id = c.audience_id LEFT JOIN campaign_schedules s ON s.campaign_id = c.id
                WHERE c.organization_id = ? AND c.status <> 'CANCELED' ORDER BY c.updated_at DESC""", (rs, i) -> {
            List<Integer> days = new ArrayList<>();
            int mask = rs.getInt("days_of_week");
            for (int d = 1; d <= 7; d++) if ((mask & (1 << (d - 1))) != 0) days.add(d);
            ScheduleInput sch = rs.getString("kind") == null ? null : new ScheduleInput(rs.getString("kind"), days,
                    rs.getTime("local_time").toLocalTime().toString(), rs.getString("time_zone"), rs.getObject("start_date", LocalDate.class),
                    rs.getObject("end_date", LocalDate.class));
            return new CampaignView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"), rs.getString("pause_reason"),
                    rs.getString("channel"), rs.getObject("template_id", UUID.class), rs.getString("subject"), rs.getString("body"),
                    rs.getObject("media_asset_id", UUID.class), rs.getString("link_url"),
                    rs.getString("audience") == null ? null : Json.read(rs.getString("audience"), AutomationDefinition.Audience.class),
                    rs.getInt("max_recipients_per_run"), rs.getLong("budget_per_run_minor"), rs.getLong("monthly_budget_minor"), rs.getInt("frequency_cap_days"),
                    sch, rs.getTimestamp("next_run_at") == null ? null : rs.getTimestamp("next_run_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
        }, org);
    }

    public record Estimate(long eligible, long perRun, long unitCostMinor, long estimatedRunCostMinor, String currency, boolean priced,
                           long walletAvailableMinor, String limitingFactor) {}

    public Estimate estimate(UUID org, UUID id) {
        var c = load(org, id);
        AutomationDefinition.Audience a = Json.read(jdbc.queryForObject("SELECT filter::text FROM campaign_audiences WHERE id = ?", String.class, c.get("audience_id")),
                AutomationDefinition.Audience.class);
        String channel = (String) c.get("channel");
        long eligible = eligibleCount(org, a, channel);
        String currency = jdbc.queryForObject("SELECT currency FROM credit_wallets WHERE organization_id = ?", String.class, org);
        long available = jdbc.queryForObject("SELECT balance_minor - reserved_minor FROM credit_wallets WHERE organization_id = ?", Long.class, org);
        String country = jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, org);
        var rate = rates.find(c.get("media_asset_id") != null && "SMS".equals(channel) ? "MMS" : channel, country, "WHATSAPP".equals(channel) ? "MARKETING" : "ANY", currency);
        long unit = rate.map(UsageRates.Rate::unitPriceMinor).orElse(0L);
        long perRun = Math.min(eligible, ((Number) c.get("max_recipients_per_run")).longValue());
        String limiting = perRun < eligible ? "MAX_RECIPIENTS" : null;
        long budget = ((Number) c.get("budget_per_run_minor")).longValue();
        if (unit > 0 && perRun * unit > budget) { perRun = budget / unit; limiting = "BUDGET_PER_RUN"; }
        if (unit > 0 && perRun * unit > available) { perRun = available / unit; limiting = "WALLET_BALANCE"; }
        return new Estimate(eligible, perRun, unit, perRun * unit, currency, rate.isPresent(), available, rate.isEmpty() ? "USAGE_RATE_NOT_CONFIGURED" : limiting);
    }

    long eligibleCount(UUID org, AutomationDefinition.Audience a, String channel) {
        MapSqlParameterSource p = new MapSqlParameterSource("org", org).addValue("ch", channel);
        String addr = "EMAIL".equals(channel) ? "c.email::text" : "c.phone_e164";
        Long n = named.queryForObject("SELECT count(*) FROM contacts c WHERE c.organization_id = :org" + InstanceFactory.audienceSql(a, p) + " AND " + addr + """
                 IS NOT NULL AND EXISTS (SELECT 1 FROM contact_consents cc WHERE cc.contact_id = c.id AND cc.channel = :ch AND cc.purpose = 'MARKETING' AND cc.status = 'GRANTED')
                  AND NOT EXISTS (SELECT 1 FROM suppression_list sl WHERE sl.organization_id = c.organization_id AND sl.channel = :ch AND sl.address = """ + addr + ")", p, Long.class);
        return n == null ? 0 : n;
    }

    public record RunView(UUID id, Instant scheduledFor, String status, String blockedReason, int eligible, int queued, int skipped, long estimatedCostMinor,
                          long delivered, long failed, long replied, Instant completedAt) {}

    public List<RunView> runs(UUID org, UUID campaignId) {
        load(org, campaignId);
        return jdbc.query("""
                SELECT r.id, r.scheduled_for, r.status, r.blocked_reason, r.eligible_count, r.queued_count, r.skipped_count, r.estimated_cost_minor,
                  (SELECT count(*) FROM messages m WHERE m.origin_type = 'CAMPAIGN' AND m.origin_id = r.id AND m.status IN ('DELIVERED','READ','REPLIED')),
                  (SELECT count(*) FROM messages m WHERE m.origin_type = 'CAMPAIGN' AND m.origin_id = r.id AND m.status = 'FAILED'),
                  (SELECT count(*) FROM messages m WHERE m.origin_type = 'CAMPAIGN' AND m.origin_id = r.id AND m.status = 'REPLIED'),
                  r.completed_at
                FROM campaign_runs r WHERE r.campaign_id = ? ORDER BY r.scheduled_for DESC LIMIT 100""",
                (rs, i) -> new RunView(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11),
                        rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant()), campaignId);
    }

    void upsertSchedule(UUID org, UUID id, ScheduleInput s, boolean computeNext) {
        try { ZoneId.of(s.timeZone()); } catch (Exception e) { throw ApiException.badRequest("Invalid time zone"); }
        int mask = 0;
        if ("ONCE".equals(s.kind()) || "DAILY".equals(s.kind())) mask = 127;
        else {
            if (s.daysOfWeek() == null || s.daysOfWeek().isEmpty()) throw ApiException.badRequest("Choose the weekdays");
            for (int d : s.daysOfWeek()) mask |= 1 << (d - 1);
        }
        Instant next = computeNext ? nextRun(s, Instant.now()) : null;
        jdbc.update("""
                INSERT INTO campaign_schedules (campaign_id, organization_id, kind, days_of_week, local_time, time_zone, start_date, end_date, next_run_at)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (campaign_id) DO UPDATE SET kind = EXCLUDED.kind, days_of_week = EXCLUDED.days_of_week,
                  local_time = EXCLUDED.local_time, time_zone = EXCLUDED.time_zone, start_date = EXCLUDED.start_date, end_date = EXCLUDED.end_date,
                  next_run_at = EXCLUDED.next_run_at""", id, org, s.kind(), mask, LocalTime.parse(s.localTime()), s.timeZone(), s.startDate(), s.endDate(),
                next == null ? null : Timestamp.from(next));
    }

    /** Next run strictly after {@code after}, within [startDate, endDate]; null when the schedule is exhausted. */
    public static Instant nextRun(ScheduleInput s, Instant after) {
        ZoneId zone = ZoneId.of(s.timeZone());
        Instant from = after;
        Instant startAt = s.startDate().atStartOfDay(zone).toInstant();
        if (from.isBefore(startAt)) from = startAt.minusSeconds(1);
        List<Integer> days = "WEEKLY".equals(s.kind()) || "CUSTOM_DAYS".equals(s.kind()) ? s.daysOfWeek() : List.of(1, 2, 3, 4, 5, 6, 7);
        Instant next = TimeRules.nextOccurrence(from, new AutomationDefinition.Schedule("WEEKLY", s.localTime(), days, null), zone);
        if (s.endDate() != null && next.atZone(zone).toLocalDate().isAfter(s.endDate())) return null;
        return next;
    }

    ScheduleInput schedule(UUID org, UUID id) {
        return list(org).stream().filter(c -> c.id().equals(id)).findFirst().map(CampaignView::schedule).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    Map<String, Object> load(UUID org, UUID id) {
        var r = jdbc.queryForList("SELECT * FROM campaigns WHERE id = ? AND organization_id = ?", id, org);
        if (r.isEmpty()) throw ApiException.notFound("Campaign");
        return r.getFirst();
    }

    private void validate(UUID org, CampaignInput in) {
        if ("WHATSAPP".equals(in.channel()) && in.templateId() == null) throw ApiException.badRequest("WhatsApp campaigns require an approved template");
        if (!"WHATSAPP".equals(in.channel()) && in.templateId() == null && (in.body() == null || in.body().isBlank())) throw ApiException.badRequest("Write the message");
        if ("EMAIL".equals(in.channel()) && in.templateId() == null && (in.subject() == null || in.subject().isBlank())) throw ApiException.badRequest("Email subject required");
        if (in.linkUrl() != null && !in.linkUrl().isBlank() && !in.linkUrl().startsWith("https://")) throw ApiException.badRequest("Links must use https");
        if (in.templateId() != null) {
            var t = jdbc.queryForList("SELECT channel, purpose FROM message_templates WHERE id = ? AND organization_id = ?", in.templateId(), org);
            if (t.isEmpty()) throw ApiException.notFound("Template");
            if (!in.channel().equals(t.getFirst().get("channel"))) throw ApiException.badRequest("Template channel mismatch");
            if (!"MARKETING".equals(t.getFirst().get("purpose"))) throw ApiException.badRequest("Campaign templates must have MARKETING purpose");
        }
        if (in.mediaAssetId() != null) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE id = ? AND organization_id = ?", Integer.class, in.mediaAssetId(), org);
            if (n == null || n == 0) throw ApiException.notFound("Image");
        }
        if (in.budgetPerRunMinor() > in.monthlyBudgetMinor() && in.monthlyBudgetMinor() > 0) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Per-run budget exceeds monthly budget");
    }
}
