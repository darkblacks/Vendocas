package com.platform.crm;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.shared.jobs.JobService;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** CRM views of the store's customers: spreadsheet (table), kanban by stage, the customer card and the home summary. */
@RestController
@RequestMapping("/api/v1/crm")
@Tag(name = "crm")
public class CrmController {
    static final Map<String, String> SORTS = Map.of(
            "name", "c.name", "lastPurchase", "m.last_purchase_at", "totalSpent", "m.total_spent_minor", "orders", "m.orders_count",
            "rfm", "(COALESCE(m.recency_score,0) + COALESCE(m.frequency_score,0) + COALESCE(m.monetary_score,0))", "created", "c.created_at");
    private final NamedParameterJdbcTemplate named;
    private final JobService jobs;
    private final com.platform.billing.EntitlementService entitlements;

    public CrmController(NamedParameterJdbcTemplate named, JobService jobs, com.platform.billing.EntitlementService entitlements) {
        this.named = named;
        this.jobs = jobs;
        this.entitlements = entitlements;
    }

    public record WhatsappStatus(String status) {}

    /** "Does this number have WhatsApp?" — Yes / No / Don't know, as told by the store. */
    @PutMapping("/customers/{id}/whatsapp")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, String> setWhatsapp(@PathVariable UUID id, @RequestBody WhatsappStatus body) {
        if (body == null || !Set.of("YES", "NO", "UNKNOWN").contains(body.status())) throw ApiException.badRequest("status must be YES, NO or UNKNOWN");
        int n = named.update("UPDATE contacts SET whatsapp_status = :s, updated_at = now() WHERE id = :id AND organization_id = :org",
                new MapSqlParameterSource("s", body.status()).addValue("id", id).addValue("org", TenantContext.current().orgId()));
        if (n == 0) throw ApiException.notFound("Contact");
        return Map.of("status", body.status());
    }

    public record CustomerRow(UUID id, String name, String email, String phone, String stage, Integer recency, Integer frequency, Integer monetary,
                              int orders, long totalSpentMinor, String currency, Instant lastPurchaseAt, List<String> channels, List<String> tags,
                              boolean vip, String whatsappStatus, Instant nextExpectedAt, List<String> personas) {}

    public record Page<T>(List<T> items, long total, int page, int size) {}

    private static CustomerRow row(ResultSet rs) throws SQLException {
        return new CustomerRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("email"), rs.getString("phone_e164"),
                rs.getString("stage") == null ? "VISITOR" : rs.getString("stage"), (Integer) rs.getObject("recency_score", Integer.class),
                (Integer) rs.getObject("frequency_score", Integer.class), (Integer) rs.getObject("monetary_score", Integer.class),
                rs.getInt("orders_count"), rs.getLong("total_spent_minor"), rs.getString("currency"),
                rs.getTimestamp("last_purchase_at") == null ? null : rs.getTimestamp("last_purchase_at").toInstant(),
                arr(rs, "channels"), arr(rs, "tags"), rs.getBoolean("vip"), rs.getString("whatsapp_status"),
                rs.getTimestamp("next_expected_at") == null ? null : rs.getTimestamp("next_expected_at").toInstant(), arr(rs, "personas"));
    }

    private static List<String> arr(ResultSet rs, String col) throws SQLException {
        var a = rs.getArray(col);
        return a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.email::text AS email, c.phone_e164, m.stage, m.recency_score, m.frequency_score, m.monetary_score,
                   COALESCE(m.orders_count, 0) AS orders_count, COALESCE(m.total_spent_minor, 0) AS total_spent_minor, m.currency, m.last_purchase_at,
                   ARRAY(SELECT DISTINCT cc.channel FROM contact_consents cc WHERE cc.contact_id = c.id AND cc.status = 'GRANTED' ORDER BY 1) AS channels,
                   ARRAY(SELECT t.name FROM contact_tags ct JOIN tags t ON t.id = ct.tag_id WHERE ct.contact_id = c.id ORDER BY 1) AS tags,
                   ARRAY(SELECT p.name FROM contact_personas cp JOIN personas p ON p.id = cp.persona_id WHERE cp.contact_id = c.id ORDER BY 1) AS personas,
                   COALESCE(m.vip, false) AS vip, c.whatsapp_status, m.next_expected_at
            FROM contacts c LEFT JOIN contact_metrics m ON m.contact_id = c.id
            """;

    /** The customers spreadsheet: filter by stage, channel with consent, tag and free text; sortable; paginated. */
    @GetMapping("/customers")
    @RequiresPermission(Permission.CONTACTS_READ)
    public Page<CustomerRow> customers(@RequestParam(required = false) String stage, @RequestParam(required = false) String q,
                                       @RequestParam(required = false) String channel, @RequestParam(required = false) String tag,
                                       @RequestParam(required = false) String whatsapp, @RequestParam(required = false) UUID persona,
                                       @RequestParam(defaultValue = "false") boolean vipOnly,
                                       @RequestParam(defaultValue = "lastPurchase") String sort, @RequestParam(defaultValue = "desc") String dir,
                                       @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        if (stage != null && !CrmService.STAGES.contains(stage)) throw ApiException.badRequest("Unknown stage");
        if (channel != null && !Set.of("EMAIL", "WHATSAPP", "SMS").contains(channel)) throw ApiException.badRequest("Unknown channel");
        if (whatsapp != null && !Set.of("YES", "NO", "UNKNOWN").contains(whatsapp)) throw ApiException.badRequest("Unknown WhatsApp status");
        String order = SORTS.get(sort);
        if (order == null) throw ApiException.badRequest("Unknown sort");
        size = Math.max(1, Math.min(200, size));
        page = Math.max(0, page);
        var p = new MapSqlParameterSource("org", TenantContext.current().orgId()).addValue("stage", stage).addValue("channel", channel)
                .addValue("tag", tag).addValue("wa", whatsapp).addValue("persona", persona, java.sql.Types.OTHER).addValue("vipOnly", vipOnly).addValue("q", q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%")
                .addValue("limit", size).addValue("offset", page * size);
        String where = """
                WHERE c.organization_id = :org AND c.status = 'ACTIVE'
                  AND (CAST(:stage AS text) IS NULL OR COALESCE(m.stage, 'VISITOR') = CAST(:stage AS text))
                  AND (CAST(:channel AS text) IS NULL OR EXISTS (SELECT 1 FROM contact_consents cc WHERE cc.contact_id = c.id AND cc.channel = CAST(:channel AS text) AND cc.status = 'GRANTED'))
                  AND (CAST(:wa AS text) IS NULL OR c.whatsapp_status = CAST(:wa AS text))
                  AND (CAST(:persona AS uuid) IS NULL OR EXISTS (SELECT 1 FROM contact_personas cp WHERE cp.contact_id = c.id AND cp.persona_id = CAST(:persona AS uuid)))
                  AND (NOT :vipOnly OR COALESCE(m.vip, false))
                  AND (CAST(:tag AS text) IS NULL OR EXISTS (SELECT 1 FROM contact_tags ct JOIN tags t ON t.id = ct.tag_id WHERE ct.contact_id = c.id AND t.name = CAST(:tag AS text)))
                  AND (CAST(:q AS text) IS NULL OR lower(COALESCE(c.name,'')) LIKE CAST(:q AS text) OR lower(COALESCE(c.email::text,'')) LIKE CAST(:q AS text)
                       OR COALESCE(c.phone_e164,'') LIKE CAST(:q AS text))
                """;
        List<CustomerRow> items = named.query(SELECT + where + " ORDER BY " + order + ("asc".equalsIgnoreCase(dir) ? " ASC" : " DESC") +
                " NULLS LAST, c.id LIMIT :limit OFFSET :offset", p, (rs, i) -> row(rs));
        Long total = named.queryForObject("SELECT count(*) FROM contacts c LEFT JOIN contact_metrics m ON m.contact_id = c.id " + where, p, Long.class);
        return new Page<>(items, total == null ? 0 : total, page, size);
    }

    public record KanbanColumn(String stage, long count, List<CustomerRow> cards) {}

    /**
     * B2C board: first sale → recurring → in window → churn risk → churn. Automatic (cards move as time passes and purchases
     * are registered), not draggable. Customers who never bought are not on the board. Plan feature PIPELINE.
     */
    @GetMapping("/kanban")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<KanbanColumn> kanban(@RequestParam(defaultValue = "20") int perColumn) {
        UUID org = TenantContext.current().orgId();
        entitlements.require(org, com.platform.billing.EntitlementService.PIPELINE);
        int n = Math.max(1, Math.min(100, perColumn));
        List<KanbanColumn> out = new ArrayList<>();
        for (String st : CrmService.BOARD) {
            var p = new MapSqlParameterSource("org", org).addValue("stage", st).addValue("n", n);
            String where = " WHERE c.organization_id = :org AND c.status = 'ACTIVE' AND COALESCE(m.stage, 'VISITOR') = :stage ";
            Long count = named.queryForObject("SELECT count(*) FROM contacts c LEFT JOIN contact_metrics m ON m.contact_id = c.id" + where, p, Long.class);
            // Most urgent first: whoever is closest to (or furthest past) their expected return.
            var cards = named.query(SELECT + where + " ORDER BY m.next_expected_at ASC NULLS LAST, c.created_at DESC LIMIT :n", p, (rs, i) -> row(rs));
            out.add(new KanbanColumn(st, count == null ? 0 : count, cards));
        }
        return out;
    }

    public record CustomerMetrics(UUID id, String stage, Integer recency, Integer frequency, Integer monetary, int orders, long totalSpentMinor,
                                  long spent12mMinor, String currency, Instant firstPurchaseAt, Instant lastPurchaseAt, BigDecimal avgIntervalDays,
                                  Long averageTicketMinor, Instant stageChangedAt, List<String> favoriteProducts, boolean vip, BigDecimal windowDays,
                                  Instant nextExpectedAt) {}

    /** Purchase metrics shown on the customer card. */
    @GetMapping("/customers/{id}")
    @RequiresPermission(Permission.CONTACTS_READ)
    public CustomerMetrics customer(@PathVariable UUID id) {
        UUID org = TenantContext.current().orgId();
        var p = new MapSqlParameterSource("org", org).addValue("id", id);
        Long exists = named.queryForObject("SELECT count(*) FROM contacts WHERE id = :id AND organization_id = :org", p, Long.class);
        if (exists == null || exists == 0) throw ApiException.notFound("Contact");
        List<String> favorites = named.queryForList("""
                SELECT i.description FROM order_items i JOIN orders o ON o.id = i.order_id
                WHERE o.organization_id = :org AND o.contact_id = :id AND o.status = 'COMPLETED'
                GROUP BY i.description ORDER BY sum(i.quantity) DESC, max(o.occurred_at) DESC LIMIT 3""", p, String.class);
        var rows = named.query("""
                SELECT stage, recency_score, frequency_score, monetary_score, orders_count, total_spent_minor, spent_12m_minor, currency,
                       first_purchase_at, last_purchase_at, avg_interval_days, stage_changed_at, vip, window_days, next_expected_at
                FROM contact_metrics WHERE contact_id = :id AND organization_id = :org""",
                p, (rs, i) -> new CustomerMetrics(id, rs.getString(1), rs.getObject(2, Integer.class), rs.getObject(3, Integer.class), rs.getObject(4, Integer.class),
                        rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getString(8),
                        rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(),
                        rs.getBigDecimal(11), rs.getInt(5) == 0 ? null : rs.getLong(6) / rs.getInt(5), rs.getTimestamp(12).toInstant(), favorites,
                        rs.getBoolean(13), rs.getBigDecimal(14), rs.getTimestamp(15) == null ? null : rs.getTimestamp(15).toInstant()));
        return rows.stream().findFirst().orElse(new CustomerMetrics(id, "VISITOR", null, null, null, 0, 0, 0, null, null, null, null, null, null, favorites, false, null, null));
    }

    public record AlertResult(UUID automationId, String name, long revenueMinor, long sales, long sent) {}

    public record Summary(String currency, long recoveredMinor, long recoveredSales, long messagesSent, long messagesEngaged, Map<String, Long> sentByChannel,
                          long atRisk, long activeCustomers, long newThisMonth, Map<String, Long> stages, List<AlertResult> byAlert) {}

    /**
     * Home screen numbers for the current calendar month (UTC). Recovered = sales attributed to a message (last touch, 7 days).
     * Engaged = read (WhatsApp) or replied or a tracked link clicked; e-mail opens are not tracked (no pixel).
     */
    @GetMapping("/summary")
    @RequiresPermission(Permission.REPORTS_READ)
    public Summary summary() {
        UUID org = TenantContext.current().orgId();
        var p = new MapSqlParameterSource("org", org);
        var rec = named.queryForMap("""
                SELECT COALESCE(sum(amount_minor), 0) AS amount, count(*) AS n, max(currency) AS currency FROM conversion_events
                WHERE organization_id = :org AND occurred_at >= date_trunc('month', now())""", p);
        Map<String, Long> byChannel = new TreeMap<>();
        long sent = 0, opened = 0;
        for (var r : named.queryForList("""
                SELECT CASE WHEN channel = 'MMS' THEN 'SMS' ELSE channel END AS ch, count(*) AS n,
                       count(*) FILTER (WHERE status IN ('READ','REPLIED')
                         OR EXISTS (SELECT 1 FROM tracked_links tl WHERE tl.message_id = messages.id AND tl.first_clicked_at IS NOT NULL)) AS opened
                FROM messages WHERE organization_id = :org AND direction = 'OUTBOUND' AND NOT is_test
                  AND status IN ('SUBMITTED','SENT','DELIVERED','READ','REPLIED') AND created_at >= date_trunc('month', now()) GROUP BY 1""", p)) {
            long n = ((Number) r.get("n")).longValue();
            byChannel.merge((String) r.get("ch"), n, Long::sum);
            sent += n;
            opened += ((Number) r.get("opened")).longValue();
        }
        Map<String, Long> stages = new LinkedHashMap<>();
        stages.put("VISITOR", 0L);
        for (String st : CrmService.BOARD) stages.put(st, 0L);
        named.query("""
                SELECT COALESCE(m.stage, 'VISITOR') AS stage, count(*) FROM contacts c LEFT JOIN contact_metrics m ON m.contact_id = c.id
                WHERE c.organization_id = :org AND c.status = 'ACTIVE' GROUP BY 1""", p, rs -> { stages.put(rs.getString(1), rs.getLong(2)); });
        long active = stages.get("FIRST_SALE") + stages.get("RECURRING") + stages.get("IN_WINDOW");
        Long newThisMonth = named.queryForObject("""
                SELECT count(*) FROM contact_metrics WHERE organization_id = :org AND first_purchase_at >= date_trunc('month', now())""", p, Long.class);
        var byAlert = named.query("""
                SELECT d.id, d.name, COALESCE(sum(ce.amount_minor), 0), count(ce.id),
                       (SELECT count(*) FROM messages m JOIN automation_instances ai ON ai.id = m.origin_id
                         WHERE m.organization_id = :org AND m.origin_type = 'AUTOMATION' AND ai.automation_id = d.id AND NOT m.is_test
                           AND m.status IN ('SUBMITTED','SENT','DELIVERED','READ','REPLIED') AND m.created_at >= date_trunc('month', now()))
                FROM automation_definitions d LEFT JOIN conversion_events ce ON ce.automation_id = d.id AND ce.occurred_at >= date_trunc('month', now())
                WHERE d.organization_id = :org GROUP BY d.id, d.name ORDER BY 3 DESC, d.name LIMIT 20""", p,
                (rs, i) -> new AlertResult(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
        return new Summary((String) rec.get("currency"), ((Number) rec.get("amount")).longValue(), ((Number) rec.get("n")).longValue(), sent, opened,
                byChannel, stages.get("CHURN_RISK"), active, newThisMonth == null ? 0 : newThisMonth, stages, byAlert);
    }

    /** Asks for an immediate recomputation (e.g. right after an import); runs on the job worker. */
    @PostMapping("/recompute")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, Boolean> recompute() {
        UUID org = TenantContext.current().orgId();
        boolean queued = jobs.schedule(CrmJobs.TYPE, "crm:manual:" + org + ":" + (System.currentTimeMillis() / 300_000), org, Map.of("manual", true), Instant.now());
        return Map.of("queued", queued);
    }
}
