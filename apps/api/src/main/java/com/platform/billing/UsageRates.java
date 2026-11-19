package com.platform.billing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Customer-facing unit prices per channel/country, managed by platform admins. Missing rate = cannot price = blocked. */
@Service
public class UsageRates {
    private final JdbcTemplate jdbc;

    public UsageRates(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Rate(long unitPriceMinor, Long providerCostMicros) {}

    public Optional<Rate> find(String channel, String countryCode, String category, String currency) {
        return jdbc.query("""
                SELECT unit_price_minor, provider_cost_micros FROM usage_rates
                WHERE active AND channel = ? AND currency = ? AND (country_code = ? OR country_code IS NULL)
                  AND message_category IN (?, 'ANY')
                ORDER BY (country_code IS NULL), (message_category = 'ANY') LIMIT 1""",
                (rs, i) -> new Rate(rs.getLong(1), (Long) rs.getObject(2)), channel, currency, countryCode, category).stream().findFirst();
    }
}
