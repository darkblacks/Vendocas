package com.platform.analytics;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dashboard metrics. Every figure is a count of recorded facts: provider-confirmed statuses for
 * messages, completed orders from trusted sources for sales, and attributed revenue reported
 * separately with its model name (LAST_TOUCH_7D) — never mixed with confirmed totals.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "reports")
public class DashboardController {
    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Dashboard(long activeAutomations, long messagesSubmitted, long messagesDelivered, long messagesFailed, long messagesBlocked, long replies,
                            long missedCalls, long callbacksDone, long appointments, long confirmedSales, Map<String, Long> confirmedRevenueByCurrency,
                            Map<String, Long> attributedRevenueByCurrency, String attributionModel, long communicationCostMinor, String walletCurrency,
                            long walletAvailableMinor, long openTasks, long openConversations, int days, List<DailyPoint> series) {}

    public record DailyPoint(String day, long sent, long delivered, long failed, long replies) {}

    @GetMapping("/dashboard")
    @RequiresPermission(Permission.REPORTS_READ)
    public Dashboard dashboard(@RequestParam(defaultValue = "30") int days) {
        UUID org = TenantContext.current().orgId();
        int d = Math.max(1, Math.min(days, 365));
        Timestamp since = Timestamp.from(Instant.now().minus(d, ChronoUnit.DAYS));
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status IN ('SUBMITTED','SENT','DELIVERED','READ','REPLIED')) AS submitted,
                       count(*) FILTER (WHERE status IN ('DELIVERED','READ','REPLIED')) AS delivered,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       count(*) FILTER (WHERE status = 'BLOCKED') AS blocked,
                       COALESCE(sum(cost_charged_minor), 0) AS cost
                FROM messages WHERE organization_id = ? AND direction = 'OUTBOUND' AND NOT is_test AND created_at >= ?""", org, since);
        Map<String, Long> confirmed = new java.util.TreeMap<>();
        jdbc.query("SELECT currency, sum(amount_minor) FROM orders WHERE organization_id = ? AND status = 'COMPLETED' AND occurred_at >= ? GROUP BY currency",
                rs -> { confirmed.put(rs.getString(1), rs.getLong(2)); }, org, since);
        Map<String, Long> attributed = new java.util.TreeMap<>();
        jdbc.query("SELECT currency, sum(amount_minor) FROM conversion_events WHERE organization_id = ? AND occurred_at >= ? GROUP BY currency",
                rs -> { attributed.put(rs.getString(1), rs.getLong(2)); }, org, since);
        var wallet = jdbc.queryForMap("SELECT currency, balance_minor - reserved_minor AS available FROM credit_wallets WHERE organization_id = ?", org);
        List<DailyPoint> series = jdbc.query("""
                SELECT to_char(date_trunc('day', created_at), 'YYYY-MM-DD') AS day,
                  count(*) FILTER (WHERE direction = 'OUTBOUND' AND status IN ('SUBMITTED','SENT','DELIVERED','READ','REPLIED')),
                  count(*) FILTER (WHERE direction = 'OUTBOUND' AND status IN ('DELIVERED','READ','REPLIED')),
                  count(*) FILTER (WHERE direction = 'OUTBOUND' AND status = 'FAILED'),
                  count(*) FILTER (WHERE direction = 'INBOUND')
                FROM messages WHERE organization_id = ? AND NOT is_test AND created_at >= ? GROUP BY 1 ORDER BY 1""",
                (rs, i) -> new DailyPoint(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), org, since);
        return new Dashboard(
                count("SELECT count(*) FROM automation_definitions WHERE organization_id = ? AND status = 'ACTIVE'", org),
                n(m.get("submitted")), n(m.get("delivered")), n(m.get("failed")), n(m.get("blocked")),
                count("SELECT count(*) FROM messages WHERE organization_id = ? AND direction = 'INBOUND' AND created_at >= ?", org, since),
                count("SELECT count(*) FROM missed_calls WHERE organization_id = ? AND occurred_at >= ?", org, since),
                count("SELECT count(*) FROM callback_tasks WHERE organization_id = ? AND status = 'DONE' AND completed_at >= ?", org, since),
                count("SELECT count(*) FROM appointments WHERE organization_id = ? AND created_at >= ?", org, since),
                count("SELECT count(*) FROM orders WHERE organization_id = ? AND status = 'COMPLETED' AND occurred_at >= ?", org, since),
                confirmed, attributed, "LAST_TOUCH_7D", n(m.get("cost")), (String) wallet.get("currency"), n(wallet.get("available")),
                count("SELECT count(*) FROM callback_tasks WHERE organization_id = ? AND status IN ('OPEN','IN_PROGRESS')", org),
                count("SELECT count(*) FROM conversations WHERE organization_id = ? AND status = 'OPEN'", org), d, series);
    }

    public record Onboarding(boolean organization, boolean subscription, boolean channel, boolean contacts, boolean automation, boolean testSent, boolean activated) {}

    /** Progress of the self-service onboarding wizard, derived from real state. */
    @GetMapping("/onboarding")
    @RequiresPermission(Permission.ORG_READ)
    public Onboarding onboarding() {
        UUID org = TenantContext.current().orgId();
        return new Onboarding(true,
                count("SELECT count(*) FROM subscriptions WHERE organization_id = ? AND status IN ('ACTIVE','PAST_DUE','GRACE_PERIOD')", org) > 0,
                count("SELECT count(*) FROM channel_connections WHERE organization_id = ? AND status IN ('ACTIVE','DEGRADED')", org) > 0,
                count("SELECT count(*) FROM contacts WHERE organization_id = ? AND status = 'ACTIVE'", org) > 0,
                count("SELECT count(*) FROM automation_definitions WHERE organization_id = ? AND status <> 'ARCHIVED'", org) > 0,
                count("SELECT count(*) FROM messages WHERE organization_id = ? AND is_test AND status NOT IN ('BLOCKED','CANCELED')", org) > 0,
                count("SELECT count(*) FROM automation_definitions WHERE organization_id = ? AND status = 'ACTIVE'", org) > 0);
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }

    private static long n(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
}
