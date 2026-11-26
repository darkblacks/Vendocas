package com.platform.billing;

import com.platform.audit.AuditService;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.jobs.JobService;
import com.platform.shared.outbox.OutboxService;
import com.platform.tenants.Organization;
import com.platform.tenants.OrganizationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription lifecycle. Commercial policy (documented in docs/billing.md):
 * <ul>
 *   <li>One live subscription per establishment (organization).</li>
 *   <li>Upgrade: immediate, prorated difference invoiced now (card: must succeed or nothing changes).</li>
 *   <li>Downgrade: takes effect at the end of the current period, no refund for the remainder.</li>
 *   <li>Cancel: at period end; data kept for the retention period; can be undone before period end.</li>
 *   <li>Reactivation after CANCELED/EXPIRED: a new subscription, configuration preserved.</li>
 *   <li>Paid service only after a provider-confirmed payment (invoice.paid / paid checkout).</li>
 * </ul>
 */
@Service
public class SubscriptionService {
    public static final Duration GRACE = Duration.ofDays(7);
    private final SubscriptionRepository subs;
    private final Catalog catalog;
    private final PaymentProvider provider;
    private final OrganizationService orgs;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxService outbox;
    private final JobService jobs;
    private final AppProperties props;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subs, Catalog catalog, PaymentProvider provider, OrganizationService orgs,
                               JdbcTemplate jdbc, AuditService audit, OutboxService outbox, JobService jobs, AppProperties props, Clock clock) {
        this.subs = subs;
        this.catalog = catalog;
        this.provider = provider;
        this.orgs = orgs;
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.jobs = jobs;
        this.props = props;
        this.clock = clock;
    }

    public record SubscriptionView(UUID id, String planCode, String planName, String status, String collectionMethod, String currency,
                                   long amountMinor, Instant currentPeriodStart, Instant currentPeriodEnd, Instant graceUntil,
                                   boolean cancelAtPeriodEnd, String pendingPlanCode) {}

    public record StartResult(UUID subscriptionId, String status, String checkoutUrl) {}

    public SubscriptionView current(UUID orgId) {
        return subs.findLive(orgId).map(this::view).orElse(null);
    }

    SubscriptionView view(Subscription s) {
        var plan = catalog.plan(s.getPlanId());
        var price = catalog.priceById(s.getPriceId());
        String pending = s.getPendingPlanId() == null ? null : catalog.plan(s.getPendingPlanId()).code();
        return new SubscriptionView(s.getId(), plan.code(), plan.name(), s.getStatus().name(), s.getCollectionMethod(), price.currency(),
                price.unitAmountMinor(), s.getCurrentPeriodStart(), s.getCurrentPeriodEnd(), s.getGraceUntil(), s.isCancelAtPeriodEnd(), pending);
    }

    /** Starts a subscription. method = CARD (hosted checkout) or INVOICE (business invoicing). */
    @Transactional
    public StartResult start(UUID orgId, UUID userId, String planCode, String method) {
        var live = subs.findLive(orgId);
        if (live.isPresent()) {
            Subscription s = live.get();
            if (s.getStatus() == SubscriptionStatus.DRAFT || s.getStatus() == SubscriptionStatus.CHECKOUT_PENDING) {
                transition(s, SubscriptionStatus.CANCELED, "Superseded by a new checkout", "user:" + userId, null);
                subs.flush();
            } else {
                throw ApiException.conflict("This establishment already has a subscription; change plan instead");
            }
        }
        Organization org = orgs.get(orgId);
        var plan = catalog.plan(planCode);
        var price = catalog.price(plan.id(), org.getMarketCode(), org.getBillingCurrency())
                .orElseThrow(() -> ApiException.badRequest("Plan is not on sale in this market yet"));
        boolean invoice = "INVOICE".equals(method);
        if (invoice) requireBusinessCustomer(orgId);
        String customerId = ensureCustomer(org);

        Subscription s = new Subscription();
        s.setId(Ids.newId());
        s.setOrganizationId(orgId);
        s.setPlanId(plan.id());
        s.setPriceId(price.id());
        s.setStatus(SubscriptionStatus.DRAFT);
        s.setCollectionMethod(invoice ? "SEND_INVOICE" : "CHARGE_AUTOMATICALLY");
        s.setProvider(provider.name());
        s.setCreatedAt(clock.instant());
        s.setUpdatedAt(clock.instant());
        subs.saveAndFlush(s);
        recordEvent(s, null, SubscriptionStatus.DRAFT, null, plan.id(), "Subscription created", "user:" + userId, null);

        String url = null;
        if (invoice) {
            var res = provider.createInvoiceSubscription(customerId, price.stripePriceId(), s.getId(), props.stripe().invoiceDaysUntilDue());
            s.setProviderSubscriptionId(res.providerSubscriptionId());
            transition(s, SubscriptionStatus.PAYMENT_PENDING, "Invoice billing requested; awaiting first payment", "user:" + userId, null);
        } else {
            String base = props.publicUrl();
            var res = provider.createSubscriptionCheckout(customerId, price.stripePriceId(), s.getId(),
                    base + "/app/billing?checkout=success", base + "/app/billing?checkout=canceled");
            s.setProviderCheckoutId(res.sessionId());
            url = res.url();
            transition(s, SubscriptionStatus.CHECKOUT_PENDING, "Checkout session created", "user:" + userId, null);
        }
        audit.user(orgId, userId, "subscription.started", "subscription", s.getId(), Map.of("plan", planCode, "method", method));
        return new StartResult(s.getId(), s.getStatus().name(), url);
    }

    @Transactional
    public SubscriptionView changePlan(UUID orgId, UUID userId, String planCode) {
        Subscription s = subs.findLive(orgId).orElseThrow(() -> ApiException.notFound("Subscription"));
        if (s.getStatus() != SubscriptionStatus.ACTIVE) throw ApiException.invalidState("Plan changes require an ACTIVE subscription");
        var target = catalog.plan(planCode);
        var current = catalog.plan(s.getPlanId());
        if (target.id().equals(current.id())) {
            s.setPendingPlanId(null);
            return view(s);
        }
        Organization org = orgs.get(orgId);
        var price = catalog.price(target.id(), org.getMarketCode(), catalog.priceById(s.getPriceId()).currency())
                .orElseThrow(() -> ApiException.badRequest("Target plan not available in the subscription currency"));
        if (target.tier() > current.tier()) {
            provider.changePrice(s.getProviderSubscriptionId(), price.stripePriceId(), true, "CHARGE_AUTOMATICALLY".equals(s.getCollectionMethod()));
            UUID from = s.getPlanId();
            s.setPlanId(target.id());
            s.setPriceId(price.id());
            s.setPendingPlanId(null);
            s.setUpdatedAt(clock.instant());
            recordEvent(s, s.getStatus(), s.getStatus(), from, target.id(), "Upgrade (prorated)", "user:" + userId, null);
        } else {
            s.setPendingPlanId(target.id());
            s.setUpdatedAt(clock.instant());
            Instant at = s.getCurrentPeriodEnd() == null ? clock.instant() : s.getCurrentPeriodEnd();
            jobs.schedule("billing.apply_downgrade", "downgrade:" + s.getId() + ":" + at.getEpochSecond(), orgId,
                    Map.of("subscriptionId", s.getId().toString()), at);
            recordEvent(s, s.getStatus(), s.getStatus(), s.getPlanId(), target.id(), "Downgrade scheduled for period end", "user:" + userId, null);
        }
        audit.user(orgId, userId, "subscription.plan_change", "subscription", s.getId(), Map.of("to", planCode, "org", org.getId().toString()));
        return view(s);
    }

    /** Executed by the job scheduled at period end. */
    @Transactional
    public void applyPendingDowngrade(UUID subscriptionId) {
        Subscription s = subs.lockById(subscriptionId).orElseThrow();
        if (s.getPendingPlanId() == null || s.getStatus().isTerminal()) return;
        String market = orgs.get(s.getOrganizationId()).getMarketCode();
        var price = catalog.price(s.getPendingPlanId(), market, catalog.priceById(s.getPriceId()).currency()).orElseThrow();
        provider.changePrice(s.getProviderSubscriptionId(), price.stripePriceId(), false, false);
        UUID from = s.getPlanId();
        s.setPlanId(s.getPendingPlanId());
        s.setPriceId(price.id());
        s.setPendingPlanId(null);
        s.setUpdatedAt(clock.instant());
        recordEvent(s, s.getStatus(), s.getStatus(), from, s.getPlanId(), "Downgrade applied at period end", "system:scheduler", null);
        outbox.publish(s.getOrganizationId(), "subscription", s.getId(), "subscription.plan_changed", Map.of("planId", s.getPlanId().toString()));
    }

    @Transactional
    public SubscriptionView cancel(UUID orgId, UUID userId, boolean cancel) {
        Subscription s = subs.findLive(orgId).orElseThrow(() -> ApiException.notFound("Subscription"));
        if (s.getProviderSubscriptionId() == null) {
            transition(s, SubscriptionStatus.CANCELED, "Canceled before payment", "user:" + userId, null);
            return view(s);
        }
        provider.setCancelAtPeriodEnd(s.getProviderSubscriptionId(), cancel);
        s.setCancelAtPeriodEnd(cancel);
        s.setUpdatedAt(clock.instant());
        recordEvent(s, s.getStatus(), s.getStatus(), null, null, cancel ? "Cancellation requested for period end" : "Cancellation withdrawn",
                "user:" + userId, null);
        audit.user(orgId, userId, cancel ? "subscription.cancel_requested" : "subscription.cancel_withdrawn", "subscription", s.getId(), Map.of());
        return view(s);
    }

    public String portalUrl(UUID orgId) {
        String customer = customerId(orgId);
        if (customer == null) throw ApiException.invalidState("No billing customer yet");
        return provider.billingPortalUrl(customer, props.publicUrl() + "/app/billing");
    }

    // ---- state machine ----
    /** Applies a validated transition and appends to subscription_events. Emits an outbox event. */
    public void transition(Subscription s, SubscriptionStatus to, String reason, String actor, String providerEventId) {
        SubscriptionStatus from = s.getStatus();
        if (from != to && !from.canTransitionTo(to)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Illegal subscription transition " + from + " → " + to);
        }
        s.setStatus(to);
        s.setUpdatedAt(clock.instant());
        if (to == SubscriptionStatus.CANCELED) s.setCanceledAt(clock.instant());
        if (to == SubscriptionStatus.PAST_DUE && s.getGraceUntil() == null) s.setGraceUntil(clock.instant().plus(GRACE));
        if (to == SubscriptionStatus.ACTIVE) s.setGraceUntil(null);
        recordEvent(s, from, to, null, null, reason, actor, providerEventId);
        if (from != to) {
            outbox.publish(s.getOrganizationId(), "subscription", s.getId(), "subscription.status_changed",
                    Map.of("from", from.name(), "to", to.name()));
        }
    }

    void recordEvent(Subscription s, SubscriptionStatus from, SubscriptionStatus to, UUID fromPlan, UUID toPlan, String reason, String actor, String providerEventId) {
        jdbc.update("""
                INSERT INTO subscription_events (id, subscription_id, organization_id, from_status, to_status, from_plan_id, to_plan_id, reason, actor, provider_event_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)""", Ids.newId(), s.getId(), s.getOrganizationId(), from == null ? null : from.name(), to.name(),
                fromPlan, toPlan, reason, actor, providerEventId);
    }

    private void requireBusinessCustomer(UUID orgId) {
        var p = orgs.taxProfile(orgId);
        if (!"BUSINESS".equals(p.customerType()) || p.taxId() == null || p.taxId().isBlank()) {
            throw ApiException.badRequest("Invoice billing requires a business tax identifier (CNPJ, EIN or VAT ID)");
        }
    }

    String customerId(UUID orgId) {
        var r = jdbc.queryForList("SELECT provider_customer_id FROM billing_customers WHERE organization_id = ?", String.class, orgId);
        return r.isEmpty() ? null : r.getFirst();
    }

    private String ensureCustomer(Organization o) {
        var tax = orgs.taxProfile(o.getId());
        String existing = customerId(o.getId());
        String email = o.getFinanceEmail() != null ? o.getFinanceEmail() : o.getContactEmail();
        String id = provider.ensureCustomer(new PaymentProvider.CustomerInfo(o.getId(), o.getLegalName(), email, o.getBillingCountryCode(),
                o.getAddressLine1(), o.getAddressLine2(), o.getCity(), o.getRegion(), o.getPostalCode(), o.getLocale(), tax.taxIdType(),
                tax.taxId()), existing);
        if (existing == null) {
            jdbc.update("INSERT INTO billing_customers (organization_id, provider, provider_customer_id) VALUES (?,?,?)", o.getId(), provider.name(), id);
        }
        return id;
    }

    public String ensureCustomerId(UUID orgId) { return ensureCustomer(orgs.get(orgId)); }
}
