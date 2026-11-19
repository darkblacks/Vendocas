package com.platform.billing;

import com.platform.audit.AuditService;
import com.platform.notifications.NotificationService;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.crypto.Tokens;
import com.platform.shared.jobs.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies Stripe events to the local billing mirror. Parsing is done on the raw JSON with
 * fallbacks for both pre- and post-2025 API shapes (e.g. invoice.parent.subscription_details vs
 * invoice.subscription), so a Stripe API version bump does not silently break billing.
 * The whole event is one transaction: on failure nothing (not even the receipt) is committed and
 * Stripe redelivers.
 */
@Service
public class StripeEventProcessor {
    private static final Logger log = LoggerFactory.getLogger(StripeEventProcessor.class);
    private final JdbcTemplate jdbc;
    private final SubscriptionRepository subs;
    private final SubscriptionService subscriptions;
    private final Catalog catalog;
    private final CreditWalletService wallet;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JobService jobs;
    private final Clock clock;

    public StripeEventProcessor(JdbcTemplate jdbc, SubscriptionRepository subs, SubscriptionService subscriptions, Catalog catalog,
                                CreditWalletService wallet, NotificationService notifications, AuditService audit, JobService jobs, Clock clock) {
        this.jdbc = jdbc;
        this.subs = subs;
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.wallet = wallet;
        this.notifications = notifications;
        this.audit = audit;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional
    public void process(String payload) {
        JsonNode event = Json.read(payload);
        String eventId = event.path("id").asString();
        String type = event.path("type").asString();
        int inserted = jdbc.update("""
                INSERT INTO external_event_receipts (id, provider, provider_event_id, event_type, status, payload_sha256)
                VALUES (?, 'STRIPE', ?, ?, 'RECEIVED', ?) ON CONFLICT (provider, provider_event_id) DO NOTHING""",
                Ids.newId(), eventId, type, Tokens.sha256Hex(payload));
        if (inserted == 0) return; // duplicate delivery
        JsonNode obj = event.path("data").path("object");
        String outcome = switch (type) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" -> checkoutCompleted(obj, eventId);
            case "checkout.session.expired" -> checkoutExpired(obj, eventId);
            case "invoice.created", "invoice.finalized", "invoice.updated", "invoice.voided" -> { upsertInvoice(obj); yield "PROCESSED"; }
            case "invoice.paid" -> invoicePaid(obj, eventId);
            case "invoice.payment_failed" -> invoiceFailed(obj, eventId);
            case "invoice.marked_uncollectible" -> invoiceUncollectible(obj, eventId);
            case "invoice.overdue" -> invoiceOverdue(obj, eventId);
            case "customer.subscription.updated" -> subscriptionUpdated(obj, eventId);
            case "customer.subscription.deleted" -> subscriptionDeleted(obj, eventId);
            case "charge.dispute.created" -> disputeCreated(obj);
            case "charge.refunded" -> chargeRefunded(obj);
            default -> "IGNORED";
        };
        jdbc.update("UPDATE external_event_receipts SET status = ?, processed_at = now(), organization_id = ? WHERE provider = 'STRIPE' AND provider_event_id = ?",
                outcome, orgByCustomer(text(obj, "customer")).orElse(null), eventId);
    }

    private String checkoutCompleted(JsonNode s, String eventId) {
        JsonNode meta = s.path("metadata");
        boolean paid = "paid".equals(text(s, "payment_status"));
        if ("CREDITS".equals(text(meta, "kind"))) {
            if (!paid) return "IGNORED"; // async methods: wait for async_payment_succeeded
            UUID orgId = UUID.fromString(text(meta, "organization_id"));
            long amount = Long.parseLong(text(meta, "credit_amount_minor"));
            wallet.credit(orgId, amount, text(meta, "credit_currency"), "PURCHASE", "stripe-credits:" + text(s, "id"), "CHECKOUT", null,
                    "Credit purchase");
            audit.record(orgId, null, "PROVIDER", "credits.purchased", "wallet", orgId, Map.of("amountMinor", amount), null);
            return "PROCESSED";
        }
        String ref = text(s, "client_reference_id");
        if (ref == null) return "IGNORED";
        Subscription sub = subs.lockById(UUID.fromString(ref)).orElseThrow(() -> new IllegalStateException("Unknown subscription " + ref));
        if (text(s, "subscription") != null) sub.setProviderSubscriptionId(text(s, "subscription"));
        if (paid && sub.getStatus() != SubscriptionStatus.ACTIVE) {
            subscriptions.transition(sub, SubscriptionStatus.ACTIVE, "Checkout paid", "provider:stripe", eventId);
        } else if (!paid && sub.getStatus() == SubscriptionStatus.CHECKOUT_PENDING) {
            subscriptions.transition(sub, SubscriptionStatus.PAYMENT_PENDING, "Checkout completed; payment processing", "provider:stripe", eventId);
        }
        return "PROCESSED";
    }

    private String checkoutExpired(JsonNode s, String eventId) {
        String ref = text(s, "client_reference_id");
        if (ref == null) return "IGNORED";
        subs.lockById(UUID.fromString(ref)).filter(x -> x.getStatus() == SubscriptionStatus.CHECKOUT_PENDING)
                .ifPresent(x -> subscriptions.transition(x, SubscriptionStatus.EXPIRED, "Checkout session expired", "provider:stripe", eventId));
        return "PROCESSED";
    }

    private String invoicePaid(JsonNode inv, String eventId) {
        UUID invoiceId = upsertInvoice(inv);
        if (invoiceId == null) return "IGNORED";
        UUID orgId = orgByCustomer(text(inv, "customer")).orElseThrow();
        String paymentRef = Optional.ofNullable(text(inv, "payment_intent")).orElse("inv:" + text(inv, "id"));
        jdbc.update("""
                INSERT INTO payments (id, organization_id, invoice_id, status, currency, amount_minor, provider, provider_payment_id, settled_at)
                VALUES (?,?,?,'SUCCEEDED',?,?,'STRIPE',?,?)
                ON CONFLICT (provider_payment_id) DO UPDATE SET status='SUCCEEDED', settled_at=EXCLUDED.settled_at, updated_at=now()""",
                Ids.newId(), orgId, invoiceId, text(inv, "currency").toUpperCase(), inv.path("amount_paid").asLong(), paymentRef,
                Timestamp.from(epoch(inv.path("status_transitions").path("paid_at")).orElse(clock.instant())));
        findSubscription(inv).ifPresent(sub -> {
            periodFromInvoice(inv, sub);
            if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
                subscriptions.transition(sub, SubscriptionStatus.ACTIVE, "Invoice paid (settlement confirmed by provider)", "provider:stripe", eventId);
            } else {
                subscriptions.recordEvent(sub, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, null, null, "Renewal paid", "provider:stripe", eventId);
            }
        });
        // Fiscal document issuance is asynchronous and idempotent per invoice.
        jobs.schedule("fiscal.issue", "fiscal:" + invoiceId, orgId, Map.of("invoiceId", invoiceId.toString()), clock.instant());
        return "PROCESSED";
    }

    private String invoiceFailed(JsonNode inv, String eventId) {
        UUID invoiceId = upsertInvoice(inv);
        if (invoiceId == null) return "IGNORED";
        UUID orgId = orgByCustomer(text(inv, "customer")).orElseThrow();
        findSubscription(inv).ifPresent(sub -> {
            if (sub.getStatus() == SubscriptionStatus.ACTIVE || sub.getStatus() == SubscriptionStatus.PAYMENT_PENDING) {
                subscriptions.transition(sub, SubscriptionStatus.PAST_DUE, "Payment failed", "provider:stripe", eventId);
            }
        });
        notifications.notifyBilling(orgId, "PAYMENT_FAILED", "Payment failed",
                "We could not collect your subscription payment. Update your payment method to avoid service interruption.");
        return "PROCESSED";
    }

    private String invoiceOverdue(JsonNode inv, String eventId) {
        upsertInvoice(inv);
        findSubscription(inv).ifPresent(sub -> {
            if (sub.getStatus() == SubscriptionStatus.ACTIVE || sub.getStatus() == SubscriptionStatus.PAYMENT_PENDING) {
                subscriptions.transition(sub, SubscriptionStatus.PAST_DUE, "Invoice overdue", "provider:stripe", eventId);
            }
        });
        orgByCustomer(text(inv, "customer")).ifPresent(org -> notifications.notifyBilling(org, "INVOICE_OVERDUE", "Invoice overdue",
                "Your invoice is past its due date. Pay it to keep your automations running."));
        return "PROCESSED";
    }

    private String invoiceUncollectible(JsonNode inv, String eventId) {
        upsertInvoice(inv);
        findSubscription(inv).ifPresent(sub -> {
            if (sub.getStatus().canTransitionTo(SubscriptionStatus.SUSPENDED)) {
                subscriptions.transition(sub, SubscriptionStatus.SUSPENDED, "Invoice marked uncollectible", "provider:stripe", eventId);
            }
        });
        return "PROCESSED";
    }

    private String subscriptionUpdated(JsonNode s, String eventId) {
        Optional<Subscription> maybe = subs.lockByProviderId(text(s, "id"));
        if (maybe.isEmpty()) maybe = metadataSubscription(s.path("metadata"));
        if (maybe.isEmpty()) return "IGNORED";
        Subscription sub = maybe.get();
        sub.setProviderSubscriptionId(text(s, "id"));
        sub.setCancelAtPeriodEnd(s.path("cancel_at_period_end").asBoolean(false));
        JsonNode item = s.path("items").path("data").path(0);
        epoch(firstPresent(s.path("current_period_start"), item.path("current_period_start"))).ifPresent(sub::setCurrentPeriodStart);
        epoch(firstPresent(s.path("current_period_end"), item.path("current_period_end"))).ifPresent(sub::setCurrentPeriodEnd);
        // Only negative transitions are taken from subscription status; activation requires a paid invoice.
        switch (text(s, "status")) {
            case "unpaid" -> { if (sub.getStatus().canTransitionTo(SubscriptionStatus.SUSPENDED)) subscriptions.transition(sub, SubscriptionStatus.SUSPENDED, "Provider marked subscription unpaid", "provider:stripe", eventId); }
            case "canceled" -> { if (!sub.getStatus().isTerminal()) subscriptions.transition(sub, SubscriptionStatus.CANCELED, "Canceled at provider", "provider:stripe", eventId); }
            case "incomplete_expired" -> { if (sub.getStatus().canTransitionTo(SubscriptionStatus.EXPIRED)) subscriptions.transition(sub, SubscriptionStatus.EXPIRED, "Initial payment never completed", "provider:stripe", eventId); }
            default -> { }
        }
        return "PROCESSED";
    }

    private String subscriptionDeleted(JsonNode s, String eventId) {
        subs.lockByProviderId(text(s, "id")).filter(x -> !x.getStatus().isTerminal())
                .ifPresent(x -> subscriptions.transition(x, SubscriptionStatus.CANCELED, "Subscription ended", "provider:stripe", eventId));
        return "PROCESSED";
    }

    private String disputeCreated(JsonNode d) {
        String pi = text(d, "payment_intent");
        int n = jdbc.update("UPDATE payments SET status='DISPUTED', updated_at=now() WHERE provider_payment_id = ?", pi);
        jdbc.queryForList("SELECT organization_id FROM payments WHERE provider_payment_id = ?", UUID.class, pi).forEach(org -> {
            notifications.notifyBilling(org, "PAYMENT_DISPUTED", "Payment disputed", "A payment was disputed with your bank. Our team will contact you.");
            audit.record(org, null, "PROVIDER", "payment.disputed", "payment", pi, Map.of(), null);
        });
        return n > 0 ? "PROCESSED" : "IGNORED";
    }

    private String chargeRefunded(JsonNode c) {
        String pi = text(c, "payment_intent");
        long refunded = c.path("amount_refunded").asLong();
        long amount = c.path("amount").asLong();
        jdbc.update("UPDATE payments SET refunded_minor=?, status=?, updated_at=now() WHERE provider_payment_id=?",
                refunded, refunded >= amount ? "REFUNDED" : "PARTIALLY_REFUNDED", pi);
        return "PROCESSED";
    }

    /** Mirror of a Stripe invoice (billing document — not a fiscal document). Returns local id. */
    UUID upsertInvoice(JsonNode inv) {
        Optional<UUID> org = orgByCustomer(text(inv, "customer"));
        if (org.isEmpty()) return null;
        String status = switch (text(inv, "status")) {
            case "draft" -> "DRAFT";
            case "open" -> "OPEN";
            case "paid" -> "PAID";
            case "void" -> "VOID";
            case "uncollectible" -> "UNCOLLECTIBLE";
            default -> "DRAFT";
        };
        long tax = inv.has("tax") && !inv.path("tax").isNull() ? inv.path("tax").asLong() : 0;
        if (inv.has("total_taxes")) for (JsonNode t : inv.path("total_taxes")) tax += t.path("amount").asLong();
        boolean reverseCharge = false;
        for (JsonNode t : inv.path("total_taxes")) if ("reverse_charge".equals(text(t, "taxability_reason"))) reverseCharge = true;
        Optional<Subscription> sub = findSubscription(inv);
        boolean credits = "CREDITS".equals(text(inv.path("metadata"), "kind")) || sub.isEmpty();
        UUID id = Ids.newId();
        JsonNode line = inv.path("lines").path("data").path(0);
        return jdbc.queryForObject("""
                INSERT INTO invoices (id, organization_id, subscription_id, kind, status, collection_method, currency, subtotal_minor, tax_minor,
                                      total_minor, amount_paid_minor, amount_due_minor, reverse_charge, due_at, paid_at, period_start, period_end,
                                      number, provider, provider_invoice_id, hosted_url, pdf_url)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'STRIPE',?,?,?)
                ON CONFLICT (provider_invoice_id) DO UPDATE SET status=EXCLUDED.status, tax_minor=EXCLUDED.tax_minor, total_minor=EXCLUDED.total_minor,
                    amount_paid_minor=EXCLUDED.amount_paid_minor, amount_due_minor=EXCLUDED.amount_due_minor, paid_at=COALESCE(EXCLUDED.paid_at, invoices.paid_at),
                    number=COALESCE(EXCLUDED.number, invoices.number), hosted_url=COALESCE(EXCLUDED.hosted_url, invoices.hosted_url),
                    pdf_url=COALESCE(EXCLUDED.pdf_url, invoices.pdf_url), reverse_charge=EXCLUDED.reverse_charge, due_at=EXCLUDED.due_at, updated_at=now()
                RETURNING id""", UUID.class,
                id, org.get(), sub.map(Subscription::getId).orElse(null), credits ? "CREDITS" : "SUBSCRIPTION", status,
                "send_invoice".equals(text(inv, "collection_method")) ? "SEND_INVOICE" : "CHARGE_AUTOMATICALLY",
                text(inv, "currency").toUpperCase(), inv.path("subtotal").asLong(), tax, inv.path("total").asLong(),
                inv.path("amount_paid").asLong(), inv.path("amount_due").asLong(), reverseCharge,
                ts(epoch(inv.path("due_date"))), ts(epoch(inv.path("status_transitions").path("paid_at"))),
                ts(epoch(line.path("period").path("start"))), ts(epoch(line.path("period").path("end"))),
                text(inv, "number"), text(inv, "id"), text(inv, "hosted_invoice_url"), text(inv, "invoice_pdf"));
    }

    private void periodFromInvoice(JsonNode inv, Subscription sub) {
        JsonNode line = inv.path("lines").path("data").path(0);
        epoch(line.path("period").path("start")).ifPresent(sub::setCurrentPeriodStart);
        epoch(line.path("period").path("end")).ifPresent(sub::setCurrentPeriodEnd);
        String stripePrice = Optional.ofNullable(text(line.path("pricing").path("price_details"), "price")).orElse(text(line.path("price"), "id"));
        if (stripePrice != null) catalog.priceByStripeId(stripePrice).ifPresent(p -> {
            if (!p.id().equals(sub.getPriceId()) && sub.getPendingPlanId() == null) {
                sub.setPriceId(p.id());
                sub.setPlanId(p.planId());
            }
        });
    }

    private Optional<Subscription> findSubscription(JsonNode inv) {
        JsonNode details = inv.path("parent").path("subscription_details");
        String providerSub = Optional.ofNullable(text(details, "subscription")).orElse(text(inv, "subscription"));
        if (providerSub != null) {
            Optional<Subscription> s = subs.lockByProviderId(providerSub);
            if (s.isPresent()) return s;
        }
        Optional<Subscription> byMeta = metadataSubscription(details.path("metadata"));
        if (byMeta.isPresent()) {
            if (providerSub != null) byMeta.get().setProviderSubscriptionId(providerSub);
            return byMeta;
        }
        return metadataSubscription(inv.path("subscription_details").path("metadata"));
    }

    private Optional<Subscription> metadataSubscription(JsonNode meta) {
        String id = text(meta, "subscription_id");
        return id == null ? Optional.empty() : subs.lockById(UUID.fromString(id));
    }

    private Optional<UUID> orgByCustomer(String customerId) {
        if (customerId == null) return Optional.empty();
        return jdbc.queryForList("SELECT organization_id FROM billing_customers WHERE provider_customer_id = ?", UUID.class, customerId).stream().findFirst();
    }

    private static JsonNode firstPresent(JsonNode a, JsonNode b) { return a.isMissingNode() || a.isNull() ? b : a; }

    static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isObject()) return v.path("id").asString(null); // expanded object
        return v.asString();
    }

    private static Optional<Instant> epoch(JsonNode n) {
        return n.isMissingNode() || n.isNull() || !n.canConvertToLong() ? Optional.empty() : Optional.of(Instant.ofEpochSecond(n.asLong()));
    }

    private static Timestamp ts(Optional<Instant> i) { return i.map(Timestamp::from).orElse(null); }
}
