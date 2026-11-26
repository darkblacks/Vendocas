package com.platform.operations;

import com.platform.billing.Catalog;
import com.platform.markets.MarketService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Control Center → API: commercial configuration (markets, plan prices per market, included quotas).
 * Authenticated with CONTROL_ADMIN_TOKEN (see {@link ControlAuth}); every change is audited with the acting operator.
 */
@RestController
@RequestMapping("/internal/commercial")
public class CommercialController {
    private final ControlAuth auth;
    private final MarketService markets;
    private final Catalog catalog;
    private final JdbcTemplate jdbc;

    public CommercialController(ControlAuth auth, MarketService markets, Catalog catalog, JdbcTemplate jdbc) {
        this.auth = auth;
        this.markets = markets;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    public record MarketWithCountries(MarketService.Market market, List<MarketService.Country> countries, long merchants) {}

    @GetMapping("/markets")
    public ResponseEntity<?> markets(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        var body = markets.list().stream().map(m -> new MarketWithCountries(m, markets.countries(m.code()),
                jdbc.queryForObject("SELECT count(*) FROM organizations WHERE market_code = ?", Long.class, m.code()))).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PutMapping("/markets/{code}")
    public ResponseEntity<?> updateMarket(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token,
                                          @RequestHeader(value = ControlAuth.ACTOR_HEADER, required = false) String actorHeader,
                                          @PathVariable String code, @RequestBody MarketService.MarketUpdate u) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        String actor = ControlAuth.actor(actorHeader);
        var m = markets.update(code.toUpperCase(Locale.ROOT), u, actor);
        auth.audit(actor, "market.updated", "market", m.code(), Map.of("enabled", m.enabled(), "version", m.version()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(m);
    }

    public record PlanPricing(String planCode, String planName, int tier, List<String> features, Catalog.PriceRow price, List<Catalog.Quota> quotas,
                              long activeSubscriptions) {}

    public record MarketPricing(String market, String currency, boolean enabled, List<PlanPricing> plans) {}

    /** Prices (with Stripe ids), offers, trials and quotas of every plan on sale, per market — the "Mercados, planos e preços" screen. */
    @GetMapping("/pricing")
    public ResponseEntity<?> pricing(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        List<MarketPricing> out = new ArrayList<>();
        for (var m : markets.list()) {
            List<PlanPricing> plans = new ArrayList<>();
            for (var p : catalog.plans()) {
                if (p.tier() < 10) continue;
                var price = jdbc.query("""
                        SELECT id FROM prices WHERE plan_id = ? AND market_code = ? AND currency = ? AND active""",
                        (rs, i) -> catalog.priceById(rs.getObject(1, UUID.class)), p.id(), m.code(), m.currency()).stream().findFirst().orElse(null);
                Long subs = jdbc.queryForObject("""
                        SELECT count(*) FROM subscriptions s JOIN organizations o ON o.id = s.organization_id
                        WHERE s.plan_id = ? AND o.market_code = ? AND s.status IN ('ACTIVE','PAST_DUE','GRACE_PERIOD')""", Long.class, p.id(), m.code());
                plans.add(new PlanPricing(p.code(), p.name(), p.tier(), List.copyOf(p.features()), price, catalog.quotas(p.id(), m.code()), subs == null ? 0 : subs));
            }
            out.add(new MarketPricing(m.code(), m.currency(), m.enabled(), plans));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @PutMapping("/prices/{market}/{planCode}")
    public ResponseEntity<?> setPrice(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token,
                                      @RequestHeader(value = ControlAuth.ACTOR_HEADER, required = false) String actorHeader,
                                      @PathVariable String market, @PathVariable String planCode, @RequestBody Catalog.PriceChange c) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        String actor = ControlAuth.actor(actorHeader);
        var price = catalog.setPrice(market.toUpperCase(Locale.ROOT), planCode, c, actor);
        auth.audit(actor, "price.set", "price", price.id(), Map.of("market", price.marketCode(), "plan", planCode,
                "amountMinor", price.unitAmountMinor(), "currency", price.currency(), "stripeLinked", price.stripePriceId() != null));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(price);
    }

    public record QuotaChange(Long monthlyLimit, String fairUseNote, Boolean remove) {}

    @PutMapping("/quotas/{market}/{planCode}/{channel}")
    public ResponseEntity<?> setQuota(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token,
                                      @RequestHeader(value = ControlAuth.ACTOR_HEADER, required = false) String actorHeader,
                                      @PathVariable String market, @PathVariable String planCode, @PathVariable String channel, @RequestBody QuotaChange q) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        String actor = ControlAuth.actor(actorHeader);
        boolean remove = Boolean.TRUE.equals(q.remove());
        var quotas = catalog.setQuota(market.toUpperCase(Locale.ROOT), planCode, channel.toUpperCase(Locale.ROOT), q.monthlyLimit(), q.fairUseNote(), remove, actor);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("market", market);
        d.put("plan", planCode);
        d.put("channel", channel);
        d.put("monthlyLimit", q.monthlyLimit() == null ? "unlimited" : q.monthlyLimit());
        d.put("removed", remove);
        auth.audit(actor, "quota.set", "plan", planCode, d);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(quotas);
    }
}
