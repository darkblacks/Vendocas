package com.platform.billing;

import com.platform.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/** Server-side source of truth for plans and prices. Amounts from clients are never trusted. */
@Service
public class Catalog {
    private final JdbcTemplate jdbc;

    public Catalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PlanRow(UUID id, String code, String name, int tier, boolean promotional, Set<String> features) {}

    public record PriceRow(UUID id, UUID planId, String currency, long unitAmountMinor, String taxBehavior, String stripePriceId) {}

    public record PlanView(String code, String name, int tier, boolean promotional, List<String> features, String currency,
                           long monthlyAmountMinor, String taxBehavior, boolean purchasable) {}

    public List<PlanRow> plans() {
        Map<UUID, Set<String>> features = new HashMap<>();
        jdbc.query("SELECT plan_id, feature FROM plan_features", rs -> {
            features.computeIfAbsent(rs.getObject(1, UUID.class), k -> new TreeSet<>()).add(rs.getString(2));
        });
        return jdbc.query("SELECT id, code, name, tier, is_promotional FROM plans WHERE active ORDER BY tier",
                (rs, i) -> new PlanRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getBoolean(5),
                        features.getOrDefault(rs.getObject(1, UUID.class), Set.of())));
    }

    public PlanRow plan(String code) {
        return plans().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow(() -> ApiException.notFound("Plan"));
    }

    public PlanRow plan(UUID id) {
        return plans().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Plan"));
    }

    /** Active price in the requested currency, falling back to USD (the base currency). */
    public Optional<PriceRow> price(UUID planId, String currency) {
        List<PriceRow> rows = jdbc.query("""
                SELECT id, plan_id, currency, unit_amount_minor, tax_behavior, stripe_price_id FROM prices
                WHERE plan_id = ? AND active AND currency IN (?, 'USD') ORDER BY (currency = ?) DESC LIMIT 1""",
                (rs, i) -> new PriceRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6)), planId, currency, currency);
        return rows.stream().findFirst();
    }

    public PriceRow priceById(UUID priceId) {
        return jdbc.queryForObject("SELECT id, plan_id, currency, unit_amount_minor, tax_behavior, stripe_price_id FROM prices WHERE id = ?",
                (rs, i) -> new PriceRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6)), priceId);
    }

    public Optional<PriceRow> priceByStripeId(String stripePriceId) {
        return jdbc.query("SELECT id, plan_id, currency, unit_amount_minor, tax_behavior, stripe_price_id FROM prices WHERE stripe_price_id = ?",
                (rs, i) -> new PriceRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6)), stripePriceId).stream().findFirst();
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
}
