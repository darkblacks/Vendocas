package com.platform.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Messages included in the plan ("tudo incluso"). Each plan × market × channel may carry a monthly quota:
 * <ul>
 *   <li>under the quota → the message is INCLUDED (not charged to the credit wallet);</li>
 *   <li>quota used up → PAY_PER_USE if a usage rate exists (extra pack via credits), otherwise EXCEEDED (blocked);</li>
 *   <li>no quota row for the channel → PAY_PER_USE (previous behaviour);</li>
 *   <li>quota with no limit → INCLUDED always (fair use, watched in the Control Center).</li>
 * </ul>
 * The counting window is the subscription's current billing period (calendar month as fallback).
 */
@Service
public class QuotaService {
    private final JdbcTemplate jdbc;
    private final Catalog catalog;
    private final Clock clock;

    public QuotaService(JdbcTemplate jdbc, Catalog catalog, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.clock = clock;
    }

    public enum Mode { INCLUDED, PAY_PER_USE, EXCEEDED }

    public record Usage(String channel, Long monthlyLimit, long used, boolean unlimited, boolean payPerUse, Instant periodStart, Instant periodEnd) {
        public Integer percent() { return monthlyLimit == null || monthlyLimit == 0 ? null : (int) Math.min(100, used * 100 / monthlyLimit); }
    }

    public record Period(Instant start, Instant end) {}

    public Period period(UUID orgId) {
        var rows = jdbc.queryForList("""
                SELECT current_period_start, current_period_end FROM subscriptions WHERE organization_id = ? AND status NOT IN ('CANCELED','EXPIRED')
                  AND current_period_start IS NOT NULL AND current_period_end IS NOT NULL AND current_period_start <= now() AND current_period_end > now()""", orgId);
        if (!rows.isEmpty()) {
            return new Period(((Timestamp) rows.getFirst().get("current_period_start")).toInstant(), ((Timestamp) rows.getFirst().get("current_period_end")).toInstant());
        }
        YearMonth ym = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        return new Period(ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC), ym.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    public long used(UUID orgId, String channel, Instant since) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM messages WHERE organization_id = ? AND quota_included AND channel IN (?, ?) AND created_at >= ?
                  AND status NOT IN ('BLOCKED','CANCELED','FAILED')""", Long.class, orgId, channel, "SMS".equals(channel) ? "MMS" : channel, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    /**
     * Decides how the next message on {@code channel} is paid for. Serialises concurrent sends of the same organization
     * and channel for the rest of the caller's transaction, so two parallel sends cannot both take the last unit.
     */
    public Mode decide(UUID orgId, UUID planId, String market, String channel) {
        if (planId == null || market == null) return Mode.PAY_PER_USE;
        String quotaChannel = "MMS".equals(channel) ? "SMS" : channel;
        var quota = catalog.quota(planId, market, quotaChannel);
        if (quota.isEmpty()) return Mode.PAY_PER_USE;
        if (quota.get().unlimited()) return Mode.INCLUDED;
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ("quota:" + orgId + ":" + quotaChannel));
        long used = used(orgId, quotaChannel, period(orgId).start());
        return used < quota.get().monthlyLimit() ? Mode.INCLUDED : Mode.EXCEEDED;
    }

    /** Quota bars for the home screen and the Control Center. */
    public List<Usage> usage(UUID orgId, UUID planId, String market) {
        Period p = period(orgId);
        List<Usage> out = new ArrayList<>();
        for (String ch : List.of("WHATSAPP", "EMAIL", "SMS")) {
            var q = planId == null ? java.util.Optional.<Catalog.Quota>empty() : catalog.quota(planId, market, ch);
            long used = q.isPresent() ? used(orgId, ch, p.start()) : 0;
            out.add(new Usage(ch, q.map(Catalog.Quota::monthlyLimit).orElse(null), used, q.map(Catalog.Quota::unlimited).orElse(false),
                    q.isEmpty(), p.start(), p.end()));
        }
        return out;
    }
}
