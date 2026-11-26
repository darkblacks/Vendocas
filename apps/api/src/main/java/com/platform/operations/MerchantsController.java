package com.platform.operations;

import com.platform.billing.EntitlementService;
import com.platform.billing.QuotaService;
import com.platform.shared.api.ApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Control Center → API: the operator's CRM of merchants (the "Lojistas" spreadsheet and the merchant card). Contains tenant
 * business data, so it requires CONTROL_ADMIN_TOKEN (never the read-only status token). No credentials are returned —
 * channel connections expose only status, provider, display name and error summaries.
 */
@RestController
@RequestMapping("/internal/merchants")
public class MerchantsController {
    private final ControlAuth auth;
    private final NamedParameterJdbcTemplate named;
    private final EntitlementService entitlements;
    private final QuotaService quotas;

    public MerchantsController(ControlAuth auth, NamedParameterJdbcTemplate named, EntitlementService entitlements, QuotaService quotas) {
        this.auth = auth;
        this.named = named;
        this.entitlements = entitlements;
        this.quotas = quotas;
    }

    public record MerchantRow(UUID id, String tradeName, String contactEmail, String countryCode, String market, String planCode, String subscriptionStatus,
                              Long priceMinor, String priceCurrency, Map<String, String> channels, Map<String, Long> messagesThisMonth,
                              long providerCostMicrosThisMonth, Instant lastLoginAt, Instant createdAt, List<String> health) {}

    public record Page<T>(List<T> items, long total, int page, int size) {}

    private static final String BASE = """
            FROM organizations o
            LEFT JOIN subscriptions s ON s.organization_id = o.id AND s.status NOT IN ('CANCELED','EXPIRED')
            LEFT JOIN plans p ON p.id = s.plan_id
            LEFT JOIN prices pr ON pr.id = s.price_id
            WHERE (CAST(:market AS text) IS NULL OR o.market_code = CAST(:market AS text))
              AND (CAST(:plan AS text) IS NULL OR p.code = CAST(:plan AS text))
              AND (CAST(:status AS text) IS NULL OR COALESCE(s.status, 'NONE') = CAST(:status AS text))
              AND (CAST(:q AS text) IS NULL OR lower(o.trade_name) LIKE CAST(:q AS text) OR lower(o.legal_name) LIKE CAST(:q AS text)
                   OR lower(o.contact_email::text) LIKE CAST(:q AS text))
            """;

    private static final String SELECT = """
            SELECT o.id, o.trade_name, o.contact_email::text AS contact_email, o.country_code, o.market_code, p.code AS plan_code, s.status AS sub_status,
                   pr.unit_amount_minor, pr.currency AS price_currency, o.created_at,
                   (SELECT max(u.last_login_at) FROM organization_members m JOIN users u ON u.id = m.user_id WHERE m.organization_id = o.id) AS last_login_at,
                   (SELECT string_agg(cc.channel || ':' || cc.status, ',') FROM channel_connections cc WHERE cc.organization_id = o.id) AS channels,
                   (SELECT string_agg(x.ch || ':' || x.n, ',') FROM (SELECT CASE WHEN channel = 'MMS' THEN 'SMS' ELSE channel END AS ch, count(*) AS n FROM messages
                       WHERE organization_id = o.id AND direction = 'OUTBOUND' AND NOT is_test AND status IN ('SUBMITTED','SENT','DELIVERED','READ','REPLIED')
                         AND created_at >= date_trunc('month', now()) GROUP BY 1) x) AS sent,
                   (SELECT COALESCE(sum(COALESCE(actual_cost_micros, estimated_cost_micros, 0)), 0) FROM provider_costs pc
                       WHERE pc.organization_id = o.id AND pc.created_at >= date_trunc('month', now())) AS cost_micros
            """;

    private static Map<String, String> pairs(String s) {
        Map<String, String> m = new TreeMap<>();
        if (s != null) for (String kv : s.split(",")) { int i = kv.indexOf(':'); if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1)); }
        return m;
    }

    private static MerchantRow row(ResultSet rs) throws SQLException {
        Map<String, String> channels = pairs(rs.getString("channels"));
        Map<String, Long> sent = new TreeMap<>();
        pairs(rs.getString("sent")).forEach((k, v) -> sent.put(k, Long.parseLong(v)));
        String status = rs.getString("sub_status");
        Instant lastLogin = rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant();
        long cost = rs.getLong("cost_micros");
        Long price = rs.getObject("unit_amount_minor", Long.class);
        String currency = rs.getString("price_currency");
        List<String> health = new ArrayList<>();
        if (status == null) health.add("NO_SUBSCRIPTION");
        else if (Set.of("PAST_DUE", "GRACE_PERIOD", "SUSPENDED").contains(status)) health.add("PAYMENT_ISSUE");
        channels.forEach((ch, st) -> { if (Set.of("DEGRADED", "ERROR", "DISCONNECTED", "SUSPENDED").contains(st)) health.add("CHANNEL_" + ch + "_" + st); });
        if (!channels.containsKey("WHATSAPP")) health.add("NO_WHATSAPP");
        if (lastLogin == null || lastLogin.isBefore(Instant.now().minusSeconds(7 * 86400))) health.add("NO_LOGIN_7D");
        // Cost above revenue (USD prices only: provider costs are tracked in USD micros).
        if (price != null && "USD".equals(currency) && cost / 10_000 > price) health.add("COST_ABOVE_REVENUE");
        return new MerchantRow(rs.getObject("id", UUID.class), rs.getString("trade_name"), rs.getString("contact_email"), rs.getString("country_code"),
                rs.getString("market_code"), rs.getString("plan_code"), status, price, currency, channels, sent, cost, lastLogin,
                rs.getTimestamp("created_at").toInstant(), health);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token,
                                  @RequestParam(required = false) String q, @RequestParam(required = false) String market,
                                  @RequestParam(required = false) String plan, @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        size = Math.max(1, Math.min(200, size));
        page = Math.max(0, page);
        var p = new MapSqlParameterSource("market", market).addValue("plan", plan).addValue("status", status)
                .addValue("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%").addValue("limit", size).addValue("offset", page * size);
        var items = named.query(SELECT + BASE + " ORDER BY o.created_at DESC LIMIT :limit OFFSET :offset", p, (rs, i) -> row(rs));
        Long total = named.queryForObject("SELECT count(*) " + BASE, p, Long.class);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page<>(items, total == null ? 0 : total, page, size));
    }

    public record ChannelView(String channel, String provider, String status, String statusReason, String actionRequired, String displayName,
                              Instant lastVerifiedAt, Instant lastErrorAt) {}

    public record MerchantDetail(MerchantRow merchant, String legalName, String billingCountryCode, String taxIdType, String taxIdStatus,
                                 Instant currentPeriodEnd, List<ChannelView> channels, List<QuotaService.Usage> quotas,
                                 Map<String, Long> blockedLast7Days, long contacts, long recoveredMinorThisMonth, long recoveredSalesThisMonth) {}

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@RequestHeader(value = ControlAuth.TOKEN_HEADER, required = false) String token, @PathVariable UUID id) {
        var r = auth.reject(token);
        if (r.isPresent()) return r.get();
        var p = new MapSqlParameterSource("market", null).addValue("plan", null).addValue("status", null).addValue("q", null).addValue("id", id);
        var row = named.query(SELECT + BASE + " AND o.id = :id", p, (rs, i) -> row(rs)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Merchant"));
        var org = named.queryForMap("""
                SELECT o.legal_name, o.billing_country_code, t.tax_id_type, t.tax_id_status,
                       (SELECT current_period_end FROM subscriptions s WHERE s.organization_id = o.id AND s.status NOT IN ('CANCELED','EXPIRED')) AS period_end
                FROM organizations o LEFT JOIN tax_profiles t ON t.organization_id = o.id WHERE o.id = :id""", p);
        var channels = named.query("""
                SELECT channel, provider, status, status_reason, action_required, display_name, last_verified_at, last_error_at
                FROM channel_connections WHERE organization_id = :id ORDER BY channel""", p,
                (rs, i) -> new ChannelView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()));
        Map<String, Long> blocked = new TreeMap<>();
        named.query("""
                SELECT status_reason, count(*) FROM messages WHERE organization_id = :id AND status = 'BLOCKED' AND created_at > now() - interval '7 days'
                GROUP BY 1 ORDER BY 2 DESC LIMIT 20""", p, rs -> { blocked.put(rs.getString(1), rs.getLong(2)); });
        Long contacts = named.queryForObject("SELECT count(*) FROM contacts WHERE organization_id = :id AND status = 'ACTIVE'", p, Long.class);
        var rec = named.queryForMap("""
                SELECT COALESCE(sum(amount_minor), 0) AS amount, count(*) AS n FROM conversion_events
                WHERE organization_id = :id AND occurred_at >= date_trunc('month', now())""", p);
        var ent = entitlements.of(id);
        var periodEnd = (java.sql.Timestamp) org.get("period_end");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MerchantDetail(row, (String) org.get("legal_name"),
                (String) org.get("billing_country_code"), (String) org.get("tax_id_type"), (String) org.get("tax_id_status"),
                periodEnd == null ? null : periodEnd.toInstant(), channels, quotas.usage(id, ent.planId(), row.market()), blocked,
                contacts == null ? 0 : contacts, ((Number) rec.get("amount")).longValue(), ((Number) rec.get("n")).longValue()));
    }
}
