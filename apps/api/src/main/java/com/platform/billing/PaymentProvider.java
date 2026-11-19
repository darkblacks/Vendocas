package com.platform.billing;

import java.util.UUID;

/**
 * Billing provider port. The Stripe adapter is the first implementation; others (e.g. a Brazilian
 * PSP for Pix Automático) can be added behind this interface without touching domain rules.
 */
public interface PaymentProvider {
    String name();

    boolean configured();

    record CustomerInfo(UUID organizationId, String legalName, String email, String countryCode, String line1, String line2,
                        String city, String region, String postalCode, String preferredLocale, String taxIdType, String taxId) {}

    String ensureCustomer(CustomerInfo customer, String existingCustomerId);

    record CheckoutResult(String sessionId, String url) {}

    /** Card (and other automatic methods) subscription via hosted checkout. */
    CheckoutResult createSubscriptionCheckout(String customerId, String providerPriceId, UUID subscriptionId, String successUrl, String cancelUrl);

    record InvoiceSubscriptionResult(String providerSubscriptionId) {}

    /** Business invoicing: the provider emails an invoice that is paid by bank transfer / boleto / ACH / SEPA etc. */
    InvoiceSubscriptionResult createInvoiceSubscription(String customerId, String providerPriceId, UUID subscriptionId, int daysUntilDue);

    /** Immediate price change. {@code prorate=true} invoices the prorated difference now. */
    void changePrice(String providerSubscriptionId, String providerPriceId, boolean prorate, boolean chargeAutomatically);

    void setCancelAtPeriodEnd(String providerSubscriptionId, boolean cancel);

    String billingPortalUrl(String customerId, String returnUrl);

    CheckoutResult createCreditPurchaseCheckout(String customerId, String currency, long amountMinor, UUID organizationId,
                                                String idempotencyKey, String successUrl, String cancelUrl);
}
