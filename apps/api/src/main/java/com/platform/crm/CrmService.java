package com.platform.crm;

import com.platform.contacts.ContactEventService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Automatic customer stage and RFM scores (docs/produto/projeto-vendocas.md §6.3).
 *
 * <ul>
 *   <li>VISITOR — never bought; NEW — one purchase; RECURRING — two or more;</li>
 *   <li>VIP — top 10% by spend in the last 12 months (only once the store has at least {@value #VIP_MIN_BUYERS} buyers);</li>
 *   <li>AT_RISK — more than 1.5× the store's typical repurchase interval without buying;</li>
 *   <li>INACTIVE — more than 3× that interval, capped at 180 days.</li>
 * </ul>
 * The typical interval is the median of each repeat buyer's average gap ({@value #DEFAULT_REPURCHASE_DAYS} days until the
 * store has {@value #MIN_REPEATERS} repeat buyers), bounded to 7–365 days. Recency, frequency and monetary scores are
 * quintiles (1–5) among the store's buyers.
 *
 * <p>Runs for a whole organization daily and for one contact on every purchase. A stage change after the first
 * classification records a {@code STAGE_CHANGED} contact event, which automations can use as a trigger
 * (e.g. "Recompra / feedback" when a customer becomes AT_RISK).
 */
@Service
public class CrmService {
    public static final Set<String> STAGES = Set.of("VISITOR", "NEW", "RECURRING", "VIP", "AT_RISK", "INACTIVE");
    static final int VIP_MIN_BUYERS = 10, MIN_REPEATERS = 3, DEFAULT_REPURCHASE_DAYS = 30, INACTIVE_CAP_DAYS = 180;
    private final NamedParameterJdbcTemplate named;
    private final ContactEventService events;
    private final Clock clock;

    public CrmService(NamedParameterJdbcTemplate named, ContactEventService events, Clock clock) {
        this.named = named;
        this.events = events;
        this.clock = clock;
    }

    /** Buyers of an organization: completed orders, plus contacts imported with a last purchase date but no orders. */
    private static final String BUYERS = """
            WITH purchases AS (
              SELECT o.contact_id, o.occurred_at, o.amount_minor, o.currency FROM orders o JOIN contacts c ON c.id = o.contact_id
              WHERE o.organization_id = :org AND o.status = 'COMPLETED' AND c.status = 'ACTIVE'
            ), agg AS (
              SELECT contact_id, count(*)::int AS n, sum(amount_minor)::bigint AS total,
                     COALESCE(sum(amount_minor) FILTER (WHERE occurred_at > now() - interval '12 months'), 0)::bigint AS spent12,
                     min(occurred_at) AS first_at, max(occurred_at) AS last_at, max(currency) AS currency,
                     CASE WHEN count(*) > 1 THEN (extract(epoch FROM max(occurred_at) - min(occurred_at)) / 86400.0 / (count(*) - 1))::numeric END AS avg_int
              FROM purchases GROUP BY contact_id
            ), imported AS (
              SELECT c.id AS contact_id, 1 AS n, COALESCE(c.lifetime_value_minor, 0)::bigint AS total,
                     CASE WHEN c.last_purchase_at > now() - interval '12 months' THEN COALESCE(c.lifetime_value_minor, 0) ELSE 0 END::bigint AS spent12,
                     c.last_purchase_at AS first_at, c.last_purchase_at AS last_at, c.lifetime_value_currency AS currency, NULL::numeric AS avg_int
              FROM contacts c WHERE c.organization_id = :org AND c.status = 'ACTIVE' AND c.last_purchase_at IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM agg a WHERE a.contact_id = c.id)
            ), buyers AS (SELECT * FROM agg UNION ALL SELECT * FROM imported)
            """;

    public record OrgStats(BigDecimal repurchaseDays, Long vipThresholdMinor, int buyers) {}

    OrgStats stats(UUID org) {
        Map<String, Object> r = named.queryForMap(BUYERS + """
                SELECT count(*)::int AS buyers, count(*) FILTER (WHERE n >= 2)::int AS repeaters,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_int) FILTER (WHERE n >= 2) AS median_int,
                       percentile_cont(0.9) WITHIN GROUP (ORDER BY spent12) AS p90
                FROM buyers""", new MapSqlParameterSource("org", org));
        int buyers = ((Number) r.get("buyers")).intValue();
        int repeaters = ((Number) r.get("repeaters")).intValue();
        double days = repeaters >= MIN_REPEATERS && r.get("median_int") != null ? ((Number) r.get("median_int")).doubleValue() : DEFAULT_REPURCHASE_DAYS;
        days = Math.max(7, Math.min(365, days));
        Long vip = null;
        if (buyers >= VIP_MIN_BUYERS && r.get("p90") != null) {
            long p90 = Math.round(((Number) r.get("p90")).doubleValue());
            if (p90 > 0) vip = p90;
        }
        return new OrgStats(BigDecimal.valueOf(days).setScale(2, java.math.RoundingMode.HALF_UP), vip, buyers);
    }

    public record StageChange(UUID contactId, String from, String to) {}

    /** Recomputes every active contact of the organization. */
    @Transactional
    public List<StageChange> recompute(UUID org) { return recompute(org, null); }

    /**
     * Recomputes the organization's reference values and the metrics of one contact ({@code contactId}) or all of them
     * ({@code null}). Returns the stage changes (first classifications excluded) and records a STAGE_CHANGED event for each.
     */
    @Transactional
    public List<StageChange> recompute(UUID org, UUID contactId) {
        OrgStats s = stats(org);
        named.update("""
                INSERT INTO crm_org_stats (organization_id, repurchase_days, vip_threshold_minor, buyers, computed_at) VALUES (:org, :days, :vip, :buyers, now())
                ON CONFLICT (organization_id) DO UPDATE SET repurchase_days = EXCLUDED.repurchase_days, vip_threshold_minor = EXCLUDED.vip_threshold_minor,
                  buyers = EXCLUDED.buyers, computed_at = now()""",
                new MapSqlParameterSource("org", org).addValue("days", s.repurchaseDays()).addValue("vip", s.vipThresholdMinor()).addValue("buyers", s.buyers()));
        double days = s.repurchaseDays().doubleValue();
        long riskSecs = Math.round(days * 1.5 * 86400);
        long inactiveSecs = Math.round(Math.min(days * 3, INACTIVE_CAP_DAYS) * 86400);
        if (inactiveSecs <= riskSecs) inactiveSecs = riskSecs + 86400;
        var params = new MapSqlParameterSource("org", org).addValue("contact", contactId, java.sql.Types.OTHER)
                .addValue("riskSecs", riskSecs).addValue("inactiveSecs", inactiveSecs).addValue("vip", s.vipThresholdMinor(), java.sql.Types.BIGINT);
        List<StageChange> changes = named.query(BUYERS + """
                , scored AS (
                  SELECT b.*, ntile(5) OVER (ORDER BY last_at) AS r, ntile(5) OVER (ORDER BY n, total) AS f, ntile(5) OVER (ORDER BY total) AS m FROM buyers b
                ), everyone AS (
                  SELECT c.id AS contact_id, s.n, s.total, s.spent12, s.first_at, s.last_at, s.currency, s.avg_int, s.r, s.f, s.m
                  FROM contacts c LEFT JOIN scored s ON s.contact_id = c.id
                  WHERE c.organization_id = :org AND c.status = 'ACTIVE' AND (CAST(:contact AS uuid) IS NULL OR c.id = CAST(:contact AS uuid))
                ), classified AS (
                  SELECT e.*, CASE
                      WHEN e.n IS NULL THEN 'VISITOR'
                      WHEN now() - e.last_at > make_interval(secs => :inactiveSecs) THEN 'INACTIVE'
                      WHEN now() - e.last_at > make_interval(secs => :riskSecs) THEN 'AT_RISK'
                      WHEN CAST(:vip AS bigint) IS NOT NULL AND e.spent12 >= CAST(:vip AS bigint) THEN 'VIP'
                      WHEN e.n >= 2 THEN 'RECURRING'
                      ELSE 'NEW' END AS stage
                  FROM everyone e
                ), previous AS (
                  SELECT contact_id, stage FROM contact_metrics WHERE organization_id = :org
                ), up AS (
                  INSERT INTO contact_metrics (contact_id, organization_id, stage, recency_score, frequency_score, monetary_score, orders_count,
                      total_spent_minor, spent_12m_minor, currency, first_purchase_at, last_purchase_at, avg_interval_days, stage_changed_at, updated_at)
                  SELECT contact_id, :org, stage, r, f, m, COALESCE(n, 0), COALESCE(total, 0), COALESCE(spent12, 0), currency, first_at, last_at,
                         round(avg_int, 2), now(), now() FROM classified
                  ON CONFLICT (contact_id) DO UPDATE SET stage = EXCLUDED.stage, recency_score = EXCLUDED.recency_score,
                      frequency_score = EXCLUDED.frequency_score, monetary_score = EXCLUDED.monetary_score, orders_count = EXCLUDED.orders_count,
                      total_spent_minor = EXCLUDED.total_spent_minor, spent_12m_minor = EXCLUDED.spent_12m_minor, currency = EXCLUDED.currency,
                      first_purchase_at = EXCLUDED.first_purchase_at, last_purchase_at = EXCLUDED.last_purchase_at,
                      avg_interval_days = EXCLUDED.avg_interval_days, updated_at = now(),
                      stage_changed_at = CASE WHEN contact_metrics.stage <> EXCLUDED.stage THEN now() ELSE contact_metrics.stage_changed_at END
                  RETURNING contact_id, stage
                )
                SELECT up.contact_id, previous.stage AS old_stage, up.stage AS new_stage FROM up JOIN previous ON previous.contact_id = up.contact_id
                WHERE previous.stage <> up.stage""", params,
                (rs, i) -> new StageChange(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)));
        var now = clock.instant();
        String day = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
        for (StageChange c : changes) {
            events.record(org, c.contactId(), "STAGE_CHANGED", now, "CRM", "stage:" + c.contactId() + ":" + c.from() + ">" + c.to() + ":" + day,
                    Map.of("from", c.from(), "to", c.to()));
        }
        return changes;
    }
}
