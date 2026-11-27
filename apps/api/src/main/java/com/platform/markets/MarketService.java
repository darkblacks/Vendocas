package com.platform.markets;

import com.platform.shared.i18n.Languages;
import com.platform.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Commercial markets (BR, LATAM, NA, EU). A market decides currency, languages, preferred channel order and the
 * default sending window; prices and quotas are per market too (see {@code Catalog}). Edited from the Control Center.
 */
@Service
public class MarketService {
    public static final Set<String> CODES = Set.of("BR", "LATAM", "US", "CA", "EU");
    static final Set<String> CHANNELS = Set.of("EMAIL", "WHATSAPP", "SMS");
    private final JdbcTemplate jdbc;

    public MarketService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Market(String code, String name, String currency, String defaultLocale, List<String> locales, List<String> channelOrder,
                         LocalTime sendWindowStart, LocalTime sendWindowEnd, boolean enabled, long version) {}

    public record Country(String countryCode, String defaultLocale, String currency) {}

    public record MarketUpdate(String name, String defaultLocale, List<String> locales, List<String> channelOrder,
                               LocalTime sendWindowStart, LocalTime sendWindowEnd, Boolean enabled, Long expectedVersion) {}

    private static final String COLS = "code, name, currency, default_locale, locales, channel_order, send_window_start, send_window_end, enabled, version";

    private static Market map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Market(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), split(rs.getString(5)), split(rs.getString(6)),
                rs.getTime(7).toLocalTime(), rs.getTime(8).toLocalTime(), rs.getBoolean(9), rs.getLong(10));
    }

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public List<Market> list() {
        return jdbc.query("SELECT " + COLS + " FROM markets ORDER BY CASE code WHEN 'BR' THEN 1 WHEN 'LATAM' THEN 2 WHEN 'US' THEN 3 WHEN 'CA' THEN 4 ELSE 5 END",
                (rs, i) -> map(rs));
    }

    public Market get(String code) {
        return jdbc.query("SELECT " + COLS + " FROM markets WHERE code = ?", (rs, i) -> map(rs), code).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Market"));
    }

    public Market forOrganization(UUID orgId) {
        String code = jdbc.queryForObject("SELECT market_code FROM organizations WHERE id = ?", String.class, orgId);
        return get(code);
    }

    public List<Country> countries(String code) {
        return jdbc.query("SELECT country_code, default_locale, default_currency FROM supported_countries WHERE market_code = ? ORDER BY country_code",
                (rs, i) -> new Country(rs.getString(1), rs.getString(2), rs.getString(3)), code);
    }

    /** Operator change from the Control Center. Optimistic: {@code expectedVersion} must match when given. */
    @Transactional
    public Market update(String code, MarketUpdate u, String actor) {
        Market cur = get(code);
        if (u.expectedVersion() != null && u.expectedVersion() != cur.version()) throw ApiException.conflict("Market was changed by someone else");
        List<String> locales = u.locales() == null ? cur.locales() : u.locales();
        String defaultLocale = u.defaultLocale() == null ? cur.defaultLocale() : u.defaultLocale();
        // The platform speaks six languages with no regional variants: "pt-BR" is stored as "pt".
        if (locales.stream().anyMatch(l -> !Languages.isSupported(l)) || !Languages.isSupported(defaultLocale)) {
            throw ApiException.badRequest("Languages must be among " + Languages.SUPPORTED);
        }
        locales = locales.stream().map(Languages::of).distinct().toList();
        defaultLocale = Languages.of(defaultLocale);
        List<String> order = u.channelOrder() == null ? cur.channelOrder() : u.channelOrder();
        if (locales.isEmpty() || locales.stream().anyMatch(l -> !l.matches("^[a-z]{2}(-[A-Za-z0-9]{2,4})?$"))) throw ApiException.badRequest("Invalid locales");
        if (!locales.contains(defaultLocale)) throw ApiException.badRequest("defaultLocale must be one of locales");
        if (order.isEmpty() || !CHANNELS.containsAll(order) || order.stream().distinct().count() != order.size()) {
            throw ApiException.badRequest("channelOrder must list EMAIL, WHATSAPP and/or SMS without repetition");
        }
        LocalTime start = u.sendWindowStart() == null ? cur.sendWindowStart() : u.sendWindowStart();
        LocalTime end = u.sendWindowEnd() == null ? cur.sendWindowEnd() : u.sendWindowEnd();
        if (start.equals(end)) throw ApiException.badRequest("Send window cannot be empty");
        String name = u.name() == null || u.name().isBlank() ? cur.name() : u.name().trim();
        boolean enabled = u.enabled() == null ? cur.enabled() : u.enabled();
        jdbc.update("""
                UPDATE markets SET name = ?, default_locale = ?, locales = ?, channel_order = ?, send_window_start = ?, send_window_end = ?, enabled = ?,
                  updated_at = now(), updated_by = ?, version = version + 1 WHERE code = ?""",
                name, defaultLocale, String.join(",", locales), String.join(",", order), Time.valueOf(start), Time.valueOf(end), enabled, actor, code);
        return get(code);
    }
}
