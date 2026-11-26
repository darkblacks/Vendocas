package com.platform.billing;

import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Server-side source of truth for plans, prices and included quotas. Amounts from clients are never trusted.
 *
 * <p>Prices are per market (BR, LATAM, NA, EU) and currency. A price row with no market is a legacy global price.
 * Changing a price creates a new row and deactivates the old one: existing subscriptions keep pointing at the price
 * they bought until the operator migrates them.
 */
@Service
public class Catalog {
    static final Set<String> QUOTA_CHANNELS = Set.of("EMAIL", "WHATSAPP", "SMS");
    private final JdbcTemplate jdbc;

    public Catalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PlanRow(UUID id, String code, String name, int tier, boolean promotional, boolean active, Set<String> features) {}

    public record PriceRow(UUID id, UUID planId, String currency, long unitAmountMinor, String taxBehavior, String stripePriceId,
                           String marketCode, String offerText, int trialDays) {}

    public record PlanView(String code, String name, int tier, boolean promotional, List<String> features, String currency,
                           long monthlyAmountMinor, String taxBehavior, boolean purchasable) {}

    public record Quota(String channel, Long monthlyLimit, String fairUseNote) {
        public boolean unlimited() { return monthlyLimit == null; }
    }

    public record MarketPlanView(String code, String name, int tier, List<String> features, String market, String currency,
                                 Long monthlyAmountMinor, String offerText, int trialDays, List<Quota> quotas, boolean purchasable) {}

    private static final String PRICE_COLS = "id, plan_id, currency, unit_amount_minor, tax_behavior, stripe_price_id, market_code, offer_text, trial_days";

    private static PriceRow price(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PriceRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9));
    }

    /** Every plan, including retired ones that existing subscriptions may still reference. */
    public List<PlanRow> allPlans() {
        Map<UUID, Set<String>> features = new HashMap<>();
        jdbc.query("SELECT plan_id, feature FROM plan_features", rs -> {
            features.computeIfAbsent(rs.getObject(1, UUID.class), k -> new TreeSet<>()).add(rs.getString(2));
        });
        return jdbc.query("SELECT id, code, name, tier, is_promotional, active FROM plans ORDER BY tier",
                (rs, i) -> new PlanRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                        rs.getBoolean(6), features.getOrDefault(rs.getObject(1, UUID.class), Set.of())));
    }

    /** Plans currently on sale. */
    public List<PlanRow> plans() { return allPlans().stream().filter(PlanRow::active).toList(); }

    /** A plan that can be bought or switched to (must be on sale). */
    public PlanRow plan(String code) {
        return plans().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow(() -> ApiException.notFound("Plan"));
    }

    /** Any plan by id, retired ones included. */
    public PlanRow plan(UUID id) {
        return allPlans().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Plan"));
    }

    /** Active price in the requested currency, falling back to USD (legacy, market-less lookup). */
    public Optional<PriceRow> price(UUID planId, String currency) { return price(planId, null, currency); }

    /**
     * Active price of a plan for a market: the market's own price first, then a legacy global price (requested currency,
     * then USD). A plan with neither is not purchasable in that market.
     */
    public Optional<PriceRow> price(UUID planId, String market, String currency) {
        return jdbc.query("SELECT " + PRICE_COLS + """
                 FROM prices WHERE plan_id = ? AND active AND (market_code = ? OR market_code IS NULL) AND currency IN (?, 'USD')
                ORDER BY (market_code IS NOT NULL) DESC, (currency = ?) DESC LIMIT 1""",
                (rs, i) -> price(rs), planId, market, currency, currency).stream().findFirst();
    }

    public PriceRow priceById(UUID priceId) {
        return jdbc.queryForObject("SELECT " + PRICE_COLS + " FROM prices WHERE id = ?", (rs, i) -> price(rs), priceId);
    }

    public Optional<PriceRow> priceByStripeId(String stripePriceId) {
        return jdbc.query("SELECT " + PRICE_COLS + " FROM prices WHERE stripe_price_id = ?", (rs, i) -> price(rs), stripePriceId).stream().findFirst();
    }

    public List<PlanView> publicPlans(String currency) {
        return plans().stream().map(p -> {
            var price = price(p.id(), currency == null ? "USD" : currency.toUpperCase());
            return new PlanView(p.code(), p.name(), p.tier(), p.promotional(), List.copyOf(p.features()),
                    price.map(PriceRow::currency).orElse("USD"), price.map(PriceRow::unitAmountMinor).orElse(0L),
                    price.map(PriceRow::taxBehavior).orElse("EXCLUSIVE"),
                    price.map(r -> r.stripePriceId() != null).orElse(false));
        }).toList();
    }

    public List<Quota> quotas(UUID planId, String market) {
        return jdbc.query("SELECT channel, monthly_limit, fair_use_note FROM plan_quotas WHERE plan_id = ? AND market_code = ? ORDER BY channel",
                (rs, i) -> new Quota(rs.getString(1), (Long) rs.getObject(2), rs.getString(3)), planId, market);
    }

    public Optional<Quota> quota(UUID planId, String market, String channel) {
        return quotas(planId, market).stream().filter(q -> q.channel().equals(channel)).findFirst();
    }

    /** The market's storefront: plans on sale with the market price, offer, trial and quotas. */
    public List<MarketPlanView> marketPlans(String market) {
        var m = jdbc.queryForList("SELECT currency, enabled FROM markets WHERE code = ?", market);
        if (m.isEmpty()) throw ApiException.notFound("Market");
        String currency = (String) m.getFirst().get("currency");
        boolean open = Boolean.TRUE.equals(m.getFirst().get("enabled"));
        return plans().stream().filter(p -> p.tier() >= 10).map(p -> {
            Optional<PriceRow> pr = jdbc.query("SELECT " + PRICE_COLS + " FROM prices WHERE plan_id = ? AND active AND market_code = ? AND currency = ?",
                    (rs, i) -> price(rs), p.id(), market, currency).stream().findFirst();
            return new MarketPlanView(p.code(), p.name(), p.tier(), List.copyOf(p.features()), market, currency,
                    pr.map(PriceRow::unitAmountMinor).orElse(null), pr.map(PriceRow::offerText).orElse(null), pr.map(PriceRow::trialDays).orElse(0),
                    quotas(p.id(), market), open && pr.isPresent() && pr.get().stripePriceId() != null);
        }).toList();
    }

    // ------------------------------------------------------------------ operator (Control Center)

    public record PriceChange(long unitAmountMinor, String stripePriceId, String offerText, Integer trialDays) {}

    /** Sets the market price of a plan. The previous row is deactivated, never edited (subscriptions keep their price). */
    @Transactional
    public PriceRow setPrice(String market, String planCode, PriceChange c, String actor) {
        PlanRow plan = plan(planCode);
        String currency = jdbc.queryForList("SELECT currency FROM markets WHERE code = ?", String.class, market).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Market"));
        if (c.unitAmountMinor() <= 0) throw ApiException.badRequest("Price must be greater than zero");
        if (c.stripePriceId() != null && !c.stripePriceId().matches("^price_[A-Za-z0-9]{6,100}$")) throw ApiException.badRequest("Invalid Stripe price id");
        int trial = c.trialDays() == null ? 0 : c.trialDays();
        if (trial < 0 || trial > 90) throw ApiException.badRequest("trialDays must be between 0 and 90");
        String offer = c.offerText() == null || c.offerText().isBlank() ? null : c.offerText().trim();
        if (offer != null && offer.length() > 200) throw ApiException.badRequest("offerText too long");
        if (c.stripePriceId() != null) {
            Integer other = jdbc.queryForObject("SELECT count(*) FROM prices WHERE stripe_price_id = ? AND NOT (plan_id = ? AND market_code IS NOT DISTINCT FROM ?)",
                    Integer.class, c.stripePriceId(), plan.id(), market);
            if (other != null && other > 0) throw ApiException.conflict("This Stripe price is already used by another plan or market");
        }
        jdbc.update("UPDATE prices SET active = false WHERE plan_id = ? AND market_code = ? AND currency = ? AND active", plan.id(), market, currency);
        UUID id = Ids.newId();
        jdbc.update("""
                INSERT INTO prices (id, plan_id, currency, unit_amount_minor, stripe_price_id, market_code, offer_text, trial_days, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?)""", id, plan.id(), currency, c.unitAmountMinor(), c.stripePriceId(), market, offer, trial, actor);
        return priceById(id);
    }

    /** Sets (or removes, when {@code remove}) the included monthly quota. {@code monthlyLimit == null} = unlimited / fair use. */
    @Transactional
    public List<Quota> setQuota(String market, String planCode, String channel, Long monthlyLimit, String note, boolean remove, String actor) {
        PlanRow plan = plan(planCode);
        if (!QUOTA_CHANNELS.contains(channel)) throw ApiException.badRequest("channel must be EMAIL, WHATSAPP or SMS");
        if (jdbc.queryForObject("SELECT count(*) FROM markets WHERE code = ?", Integer.class, market) == 0) throw ApiException.notFound("Market");
        if (monthlyLimit != null && monthlyLimit < 0) throw ApiException.badRequest("monthlyLimit cannot be negative");
        if (remove) {
            jdbc.update("DELETE FROM plan_quotas WHERE plan_id = ? AND market_code = ? AND channel = ?", plan.id(), market, channel);
        } else {
            jdbc.update("""
                    INSERT INTO plan_quotas (plan_id, market_code, channel, monthly_limit, fair_use_note, updated_by) VALUES (?,?,?,?,?,?)
                    ON CONFLICT (plan_id, market_code, channel) DO UPDATE SET monthly_limit = EXCLUDED.monthly_limit,
                      fair_use_note = EXCLUDED.fair_use_note, updated_by = EXCLUDED.updated_by, updated_at = now()""",
                    plan.id(), market, channel, monthlyLimit, note == null || note.isBlank() ? null : note.trim(), actor);
        }
        return quotas(plan.id(), market);
    }
}
