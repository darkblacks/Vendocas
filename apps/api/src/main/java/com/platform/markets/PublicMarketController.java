package com.platform.markets;

import com.platform.billing.Catalog;
import com.platform.shared.api.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Storefront data for the public site of each market (no authentication). Only enabled markets are listed. */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "markets")
public class PublicMarketController {
    private final MarketService markets;
    private final Catalog catalog;
    private final JdbcTemplate jdbc;

    public PublicMarketController(MarketService markets, Catalog catalog, JdbcTemplate jdbc) {
        this.markets = markets;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    public record MarketView(String code, String name, String currency, String defaultLocale, List<String> locales, List<String> channelOrder,
                             List<String> countries) {}

    public record CountryMarket(String countryCode, String market, String currency, String defaultLocale, boolean open) {}

    @GetMapping("/markets")
    public ResponseEntity<List<MarketView>> list() {
        var body = markets.list().stream().filter(MarketService.Market::enabled).map(m -> new MarketView(m.code(), m.name(), m.currency(),
                m.defaultLocale(), m.locales(), m.channelOrder(), markets.countries(m.code()).stream().map(MarketService.Country::countryCode).toList())).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(body);
    }

    @GetMapping("/markets/{code}/plans")
    public ResponseEntity<List<Catalog.MarketPlanView>> plans(@PathVariable String code) {
        var m = markets.get(code.toUpperCase(Locale.ROOT));
        if (!m.enabled()) throw ApiException.notFound("Market");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(catalog.marketPlans(m.code()));
    }

    /** Which market (and so which prices, language and signup form) a country belongs to. */
    @GetMapping("/countries/{countryCode}")
    public CountryMarket country(@PathVariable String countryCode) {
        String cc = countryCode.toUpperCase(Locale.ROOT);
        return jdbc.query("""
                SELECT c.country_code, c.market_code, c.default_currency, c.default_locale, m.enabled FROM supported_countries c
                JOIN markets m ON m.code = c.market_code WHERE c.country_code = ?""",
                (rs, i) -> new CountryMarket(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)), cc)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Country"));
    }
}
