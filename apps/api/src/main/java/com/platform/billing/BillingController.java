package com.platform.billing;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.crypto.Tokens;
import com.platform.tenants.OrganizationService;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
@Tag(name = "billing")
public class BillingController {
    /** Top-up amounts in minor units of the wallet currency (1 credit = 1 minor unit). */
    static final List<Long> CREDIT_PACKS = List.of(1000L, 2500L, 5000L, 10000L);
    private final Catalog catalog;
    private final SubscriptionService subscriptions;
    private final CreditWalletService wallet;
    private final PaymentProvider provider;
    private final EntitlementService entitlements;
    private final OrganizationService orgs;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final QuotaService quotas;

    public BillingController(Catalog catalog, SubscriptionService subscriptions, CreditWalletService wallet, PaymentProvider provider,
                             EntitlementService entitlements, OrganizationService orgs, JdbcTemplate jdbc, AppProperties props, QuotaService quotas) {
        this.quotas = quotas;
        this.catalog = catalog;
        this.subscriptions = subscriptions;
        this.wallet = wallet;
        this.provider = provider;
        this.entitlements = entitlements;
        this.orgs = orgs;
        this.jdbc = jdbc;
        this.props = props;
    }

    /** Public pricing (landing page). Prices shown before tax; tax is computed at checkout. */
    @GetMapping("/plans")
    public List<Catalog.PlanView> plans(@RequestParam(defaultValue = "USD") String currency) { return catalog.publicPlans(currency); }

    public record Overview(SubscriptionService.SubscriptionView subscription, EntitlementView entitlements, CreditWalletService.Wallet wallet,
                           boolean providerConfigured, List<Long> creditPacks) {}

    public record EntitlementView(String status, String planCode, List<String> features) {}

    @GetMapping("/overview")
    @RequiresPermission(Permission.BILLING_READ)
    public Overview overview() {
        UUID org = TenantContext.current().orgId();
        var e = entitlements.of(org);
        return new Overview(subscriptions.current(org), new EntitlementView(e.status() == null ? null : e.status().name(), e.planCode(),
                List.copyOf(e.features())), wallet.get(org), provider.configured(), CREDIT_PACKS);
    }

    /** Lightweight entitlement check for any member (used by the UI to show/hide features). */
    @GetMapping("/entitlements")
    @RequiresPermission(Permission.ORG_READ)
    public EntitlementView entitlements() {
        var e = entitlements.of(TenantContext.current().orgId());
        return new EntitlementView(e.status() == null ? null : e.status().name(), e.planCode(), List.copyOf(e.features()));
    }

    public record UsageView(String market, String planCode, List<QuotaService.Usage> channels) {}

    /** Included-message usage of the current billing period (quota bars on the home screen). */
    @GetMapping("/usage")
    @RequiresPermission(Permission.ORG_READ)
    public UsageView usage() {
        UUID org = TenantContext.current().orgId();
        var e = entitlements.of(org);
        String market = orgs.get(org).getMarketCode();
        return new UsageView(market, e.planCode(), quotas.usage(org, e.planId(), market));
    }

    /** Plans on sale in the caller's own market (prices in the market currency). */
    @GetMapping("/market-plans")
    @RequiresPermission(Permission.ORG_READ)
    public List<Catalog.MarketPlanView> marketPlans() { return catalog.marketPlans(orgs.get(TenantContext.current().orgId()).getMarketCode()); }

    public record StartRequest(@NotBlank String planCode, @NotBlank @Pattern(regexp = "CARD|INVOICE") String method) {}

    @PostMapping("/subscription")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.StartResult start(@Valid @RequestBody StartRequest req) {
        var t = TenantContext.current();
        return subscriptions.start(t.orgId(), t.userId(), req.planCode(), req.method());
    }

    public record ChangePlanRequest(@NotBlank String planCode) {}

    @PostMapping("/subscription/change-plan")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.SubscriptionView changePlan(@Valid @RequestBody ChangePlanRequest req) {
        var t = TenantContext.current();
        return subscriptions.changePlan(t.orgId(), t.userId(), req.planCode());
    }

    @PostMapping("/subscription/cancel")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.SubscriptionView cancel() {
        var t = TenantContext.current();
        return subscriptions.cancel(t.orgId(), t.userId(), true);
    }

    @PostMapping("/subscription/resume")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public SubscriptionService.SubscriptionView resume() {
        var t = TenantContext.current();
        return subscriptions.cancel(t.orgId(), t.userId(), false);
    }

    @PostMapping("/portal")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public Map<String, String> portal() { return Map.of("url", subscriptions.portalUrl(TenantContext.current().orgId())); }

    public record InvoiceView(UUID id, String kind, String status, String number, String currency, long subtotalMinor, long taxMinor,
                              long totalMinor, long amountDueMinor, boolean reverseCharge, Instant dueAt, Instant paidAt,
                              String hostedUrl, String pdfUrl, String fiscalStatus, String fiscalNumber, Instant createdAt) {}

    @GetMapping("/invoices")
    @RequiresPermission(Permission.BILLING_READ)
    public List<InvoiceView> invoices() {
        return jdbc.query("""
                SELECT i.id, i.kind, i.status, i.number, i.currency, i.subtotal_minor, i.tax_minor, i.total_minor, i.amount_due_minor,
                       i.reverse_charge, i.due_at, i.paid_at, i.hosted_url, i.pdf_url, d.status, d.number, i.created_at
                FROM invoices i LEFT JOIN tax_documents d ON d.invoice_id = i.id
                WHERE i.organization_id = ? ORDER BY i.created_at DESC LIMIT 100""",
                (rs, n) -> new InvoiceView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getBoolean(10), inst(rs.getTimestamp(11)),
                        inst(rs.getTimestamp(12)), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16),
                        inst(rs.getTimestamp(17))), TenantContext.current().orgId());
    }

    public record EventView(String fromStatus, String toStatus, String reason, String actor, Instant createdAt) {}

    @GetMapping("/subscription/history")
    @RequiresPermission(Permission.BILLING_READ)
    public List<EventView> history() {
        return jdbc.query("SELECT from_status, to_status, reason, actor, created_at FROM subscription_events WHERE organization_id = ? ORDER BY created_at DESC LIMIT 200",
                (rs, n) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), inst(rs.getTimestamp(5))),
                TenantContext.current().orgId());
    }

    public record TransactionView(String type, long amountMinor, long balanceAfter, String referenceType, String description, Instant createdAt) {}

    @GetMapping("/wallet/transactions")
    @RequiresPermission(Permission.BILLING_READ)
    public List<TransactionView> transactions() {
        return jdbc.query("""
                SELECT type, amount_minor, balance_after, reference_type, description, created_at FROM credit_transactions
                WHERE organization_id = ? ORDER BY created_at DESC LIMIT 200""",
                (rs, n) -> new TransactionView(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        inst(rs.getTimestamp(6))), TenantContext.current().orgId());
    }

    public record CreditPurchaseRequest(long amountMinor) {}

    @PostMapping("/wallet/purchase")
    @RequiresPermission(Permission.BILLING_MANAGE)
    public Map<String, String> buyCredits(@RequestBody CreditPurchaseRequest req) {
        if (!CREDIT_PACKS.contains(req.amountMinor())) throw ApiException.badRequest("Choose one of the available credit packs");
        var t = TenantContext.current();
        var e = entitlements.of(t.orgId());
        if (!e.serviceActive()) throw ApiException.invalidState("An active subscription is required to buy credits");
        String currency = wallet.get(t.orgId()).currency();
        String customer = subscriptions.ensureCustomerId(t.orgId());
        String key = "credits-" + t.orgId() + "-" + Tokens.random(8);
        var res = provider.createCreditPurchaseCheckout(customer, currency, req.amountMinor(), t.orgId(), key,
                props.publicUrl() + "/app/billing?credits=success", props.publicUrl() + "/app/billing?credits=canceled");
        return Map.of("url", res.url());
    }

    private static Instant inst(java.sql.Timestamp t) { return t == null ? null : t.toInstant(); }
}
