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
 * Automatic B2C pipeline stage, RFM scores and purchase metrics (docs/produto/backend-mercados-planos-crm.md).
 *
 * <p>Every customer has a <b>window</b>: the interval in which they usually come back — their own average gap between
 * purchases, or the store's typical gap while they have bought only once. With {@code days} = time since the last purchase:
 * <ul>
 *   <li>VISITOR — never bought (not on the B2C board);</li>
 *   <li>FIRST_SALE — one purchase, still inside the window;</li>
 *   <li>RECURRING — two or more purchases and bought recently (first half of their window);</li>
 *   <li>IN_WINDOW — second half of their window: the moment they usually come back;</li>
 *   <li>CHURN_RISK — past their window;</li>
 *   <li>CHURNED — past {@value #CHURN_FACTOR}× their window (or a year).</li>
 * </ul>
 * VIP is a flag, not a stage: top 10% by spend in the last 12 months, once the store has {@value #VIP_MIN_BUYERS}+ buyers.
 * The store's typical gap is the median of repeat buyers' gaps ({@value #DEFAULT_REPURCHASE_DAYS} days until there are
 * {@value #MIN_REPEATERS}), bounded to 7–365 days.
 *
 * <p>Recomputed for one customer on every purchase (PDV, integrations, API) and for the whole store daily. A stage change
 * after the first classification records a {@code STAGE_CHANGED} contact event that automations can trigger on.
 */
@Service
public class CrmService {
    public static final java.util.List<String> BOARD = java.util.List.of("FIRST_SALE", "RECURRING", "IN_WINDOW", "CHURN_RISK", "CHURNED");
    public static final Set<String> STAGES = Set.of("VISITOR", "FIRST_SALE", "RECURRING", "IN_WINDOW", "CHURN_RISK", "CHURNED");
    static final int VIP_MIN_BUYERS = 10, MIN_REPEATERS = 3, DEFAULT_REPURCHASE_DAYS = 30, CHURN_FACTOR = 3, CHURN_CAP_DAYS = 365;
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
        var params = new MapSqlParameterSource("org", org).addValue("contact", contactId, java.sql.Types.OTHER)
                .addValue("storeDays", s.repurchaseDays()).addValue("churnFactor", CHURN_FACTOR).addValue("churnCap", CHURN_CAP_DAYS)
                .addValue("vip", s.vipThresholdMinor(), java.sql.Types.BIGINT);
        List<StageChange> changes = named.query(BUYERS + """
                , scored AS (
                  SELECT b.*, ntile(5) OVER (ORDER BY last_at) AS r, ntile(5) OVER (ORDER BY n, total) AS f, ntile(5) OVER (ORDER BY total) AS m FROM buyers b
                ), everyone AS (
                  SELECT c.id AS contact_id, s.n, s.total, s.spent12, s.first_at, s.last_at, s.currency, s.avg_int, s.r, s.f, s.m,
                         GREATEST(1, COALESCE(CASE WHEN s.n >= 2 THEN s.avg_int END, CAST(:storeDays AS numeric))) AS win,
                         extract(epoch FROM now() - s.last_at) / 86400.0 AS days
                  FROM contacts c LEFT JOIN scored s ON s.contact_id = c.id
                  WHERE c.organization_id = :org AND c.status = 'ACTIVE' AND (CAST(:contact AS uuid) IS NULL OR c.id = CAST(:contact AS uuid))
                ), classified AS (
                  SELECT e.*, CASE
                      WHEN e.n IS NULL THEN 'VISITOR'
                      WHEN e.days > LEAST(e.win * :churnFactor, :churnCap) THEN 'CHURNED'
                      WHEN e.days > e.win THEN 'CHURN_RISK'
                      WHEN e.n = 1 THEN 'FIRST_SALE'
                      WHEN e.days > e.win / 2 THEN 'IN_WINDOW'
                      ELSE 'RECURRING' END AS stage,
                    (CAST(:vip AS bigint) IS NOT NULL AND e.spent12 >= CAST(:vip AS bigint)) AS is_vip
                  FROM everyone e
                ), previous AS (
                  SELECT contact_id, stage FROM contact_metrics WHERE organization_id = :org
                ), up AS (
                  INSERT INTO contact_metrics (contact_id, organization_id, stage, vip, recency_score, frequency_score, monetary_score, orders_count,
                      total_spent_minor, spent_12m_minor, currency, first_purchase_at, last_purchase_at, avg_interval_days, window_days, next_expected_at,
                      stage_changed_at, updated_at)
                  SELECT contact_id, :org, stage, is_vip, r, f, m, COALESCE(n, 0), COALESCE(total, 0), COALESCE(spent12, 0), currency, first_at, last_at,
                         round(avg_int, 2), CASE WHEN n IS NULL THEN NULL ELSE round(win, 2) END,
                         CASE WHEN last_at IS NULL THEN NULL ELSE last_at + make_interval(secs => win * 86400) END, now(), now() FROM classified
                  ON CONFLICT (contact_id) DO UPDATE SET stage = EXCLUDED.stage, vip = EXCLUDED.vip, recency_score = EXCLUDED.recency_score,
                      frequency_score = EXCLUDED.frequency_score, monetary_score = EXCLUDED.monetary_score, orders_count = EXCLUDED.orders_count,
                      total_spent_minor = EXCLUDED.total_spent_minor, spent_12m_minor = EXCLUDED.spent_12m_minor, currency = EXCLUDED.currency,
                      first_purchase_at = EXCLUDED.first_purchase_at, last_purchase_at = EXCLUDED.last_purchase_at,
                      avg_interval_days = EXCLUDED.avg_interval_days, window_days = EXCLUDED.window_days, next_expected_at = EXCLUDED.next_expected_at,
                      updated_at = now(),
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
