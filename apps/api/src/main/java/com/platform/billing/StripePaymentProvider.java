package com.platform.billing;

import com.platform.operations.PlatformSettingsService;
import com.platform.operations.StripeKeyMode;
import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.SubscriptionCreateParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class StripePaymentProvider implements PaymentProvider {
    private final AppProperties props;
    private final PlatformSettingsService settings;
    private volatile StripeClient client;

    public StripePaymentProvider(AppProperties props, PlatformSettingsService settings) {
        this.props = props;
        this.settings = settings;
    }

    @Override public String name() { return "STRIPE"; }

    @Override public boolean configured() { return props.stripe() != null && props.stripe().configured(); }

    /**
     * Fail closed before any outgoing Stripe call when the key's mode differs from the selected Stripe mode, so a
     * live key can never be used while TEST is selected (and vice versa). Incoming webhooks are not affected.
     */
    void assertModeMatches() {
        StripeKeyMode.Mode keyMode = StripeKeyMode.of(props.stripe().secretKey());
        var selected = settings.current().stripeMode();
        if (!keyMode.name().equals(selected.name())) {
            throw new ApiException(ErrorCode.PROVIDER_MODE_MISMATCH, "Stripe key mode (" + keyMode + ") does not match the selected Stripe mode ("
                    + selected + "); align STRIPE_SECRET_KEY or the platform setting");
        }
    }

    private StripeClient client() {
        if (!configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Card/invoice billing is not configured (STRIPE_SECRET_KEY)");
        assertModeMatches();
        if (client == null) {
            synchronized (this) {
                if (client == null) client = StripeClient.builder().setApiKey(props.stripe().secretKey()).setMaxNetworkRetries(2).build();
            }
        }
        return client;
    }

    @Override
    public String ensureCustomer(CustomerInfo c, String existingCustomerId) {
        try {
            var address = CustomerCreateParams.Address.builder().setCountry(c.countryCode()).setLine1(c.line1()).setLine2(c.line2())
                    .setCity(c.city()).setState(c.region()).setPostalCode(c.postalCode()).build();
            if (existingCustomerId != null) {
                client().v1().customers().update(existingCustomerId, CustomerUpdateParams.builder().setName(c.legalName()).setEmail(c.email())
                        .setAddress(CustomerUpdateParams.Address.builder().setCountry(c.countryCode()).setLine1(c.line1()).setLine2(c.line2())
                                .setCity(c.city()).setState(c.region()).setPostalCode(c.postalCode()).build()).build());
                return existingCustomerId;
            }
            var b = CustomerCreateParams.builder().setName(c.legalName()).setEmail(c.email()).setAddress(address)
                    .addPreferredLocale(c.preferredLocale()).putMetadata("organization_id", c.organizationId().toString());
            CustomerCreateParams.TaxIdData.Type type = switch (c.taxIdType() == null ? "" : c.taxIdType()) {
                case "BR_CNPJ" -> CustomerCreateParams.TaxIdData.Type.BR_CNPJ;
                case "BR_CPF" -> CustomerCreateParams.TaxIdData.Type.BR_CPF;
                case "US_EIN" -> CustomerCreateParams.TaxIdData.Type.US_EIN;
                case "EU_VAT" -> CustomerCreateParams.TaxIdData.Type.EU_VAT;
                default -> null;
            };
            if (type != null && c.taxId() != null) {
                String value = type == CustomerCreateParams.TaxIdData.Type.EU_VAT && !Character.isLetter(c.taxId().charAt(0))
                        ? ("GR".equals(c.countryCode()) ? "EL" : c.countryCode()) + c.taxId() : c.taxId();
                b.addTaxIdData(CustomerCreateParams.TaxIdData.builder().setType(type).setValue(value).build());
            }
            Customer created = client().v1().customers().create(b.build(),
                    RequestOptions.builder().setIdempotencyKey("customer-" + c.organizationId()).build());
            return created.getId();
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    @Override
    public CheckoutResult createSubscriptionCheckout(String customerId, String priceId, UUID subscriptionId, String successUrl, String cancelUrl) {
        requirePrice(priceId);
        try {
            var params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setCustomer(customerId)
                    .setClientReferenceId(subscriptionId.toString())
                    .addLineItem(SessionCreateParams.LineItem.builder().setPrice(priceId).setQuantity(1L).build())
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setAutomaticTax(SessionCreateParams.AutomaticTax.builder().setEnabled(props.stripe().automaticTax()).build())
                    .setTaxIdCollection(SessionCreateParams.TaxIdCollection.builder().setEnabled(true).build())
                    .setCustomerUpdate(SessionCreateParams.CustomerUpdate.builder()
                            .setAddress(SessionCreateParams.CustomerUpdate.Address.AUTO)
                            .setName(SessionCreateParams.CustomerUpdate.Name.AUTO).build())
                    .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                            .putMetadata("subscription_id", subscriptionId.toString()).build())
                    .putMetadata("subscription_id", subscriptionId.toString())
                    .build();
            Session s = client().v1().checkout().sessions().create(params,
                    RequestOptions.builder().setIdempotencyKey("checkout-" + subscriptionId).build());
            return new CheckoutResult(s.getId(), s.getUrl());
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    @Override
    public InvoiceSubscriptionResult createInvoiceSubscription(String customerId, String priceId, UUID subscriptionId, int daysUntilDue) {
        requirePrice(priceId);
        try {
            // Payment methods offered on the hosted invoice page (bank transfer, boleto, ACH, SEPA…) are those
            // enabled in the Stripe Dashboard invoice settings for the seller account; not hard-coded here.
            Subscription s = client().v1().subscriptions().create(SubscriptionCreateParams.builder()
                    .setCustomer(customerId)
                    .addItem(SubscriptionCreateParams.Item.builder().setPrice(priceId).build())
                    .setCollectionMethod(SubscriptionCreateParams.CollectionMethod.SEND_INVOICE)
                    .setDaysUntilDue((long) daysUntilDue)
                    .setAutomaticTax(SubscriptionCreateParams.AutomaticTax.builder().setEnabled(props.stripe().automaticTax()).build())
                    .putMetadata("subscription_id", subscriptionId.toString())
                    .build(), RequestOptions.builder().setIdempotencyKey("invoice-sub-" + subscriptionId).build());
            return new InvoiceSubscriptionResult(s.getId());
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    @Override
    public void changePrice(String providerSubscriptionId, String priceId, boolean prorate, boolean chargeAutomatically) {
        requirePrice(priceId);
        try {
            Subscription current = client().v1().subscriptions().retrieve(providerSubscriptionId);
            String itemId = current.getItems().getData().getFirst().getId();
            var b = SubscriptionUpdateParams.builder()
                    .addItem(SubscriptionUpdateParams.Item.builder().setId(itemId).setPrice(priceId).build())
                    .setProrationBehavior(prorate ? SubscriptionUpdateParams.ProrationBehavior.ALWAYS_INVOICE
                            : SubscriptionUpdateParams.ProrationBehavior.NONE);
            // Card upgrades: if the prorated charge fails, Stripe rejects the update and nothing changes.
            if (prorate && chargeAutomatically) b.setPaymentBehavior(SubscriptionUpdateParams.PaymentBehavior.ERROR_IF_INCOMPLETE);
            client().v1().subscriptions().update(providerSubscriptionId, b.build());
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    @Override
    public void setCancelAtPeriodEnd(String providerSubscriptionId, boolean cancel) {
        try {
            client().v1().subscriptions().update(providerSubscriptionId, SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(cancel).build());
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    @Override
    public String billingPortalUrl(String customerId, String returnUrl) {
        try {
            return client().v1().billingPortal().sessions().create(com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(customerId).setReturnUrl(returnUrl).build()).getUrl();
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    @Override
    public CheckoutResult createCreditPurchaseCheckout(String customerId, String currency, long amountMinor, UUID organizationId,
                                                       String idempotencyKey, String successUrl, String cancelUrl) {
        try {
            Session s = client().v1().checkout().sessions().create(SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setCustomer(customerId)
                    .addLineItem(SessionCreateParams.LineItem.builder().setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(currency.toLowerCase()).setUnitAmount(amountMinor)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName("Communication credits").build()).build()).build())
                    .setAutomaticTax(SessionCreateParams.AutomaticTax.builder().setEnabled(props.stripe().automaticTax()).build())
                    .setInvoiceCreation(SessionCreateParams.InvoiceCreation.builder().setEnabled(true).build())
                    .setSuccessUrl(successUrl).setCancelUrl(cancelUrl)
                    .putMetadata("kind", "CREDITS")
                    .putMetadata("organization_id", organizationId.toString())
                    .putMetadata("credit_amount_minor", Long.toString(amountMinor))
                    .putMetadata("credit_currency", currency)
                    .putMetadata("idempotency_key", idempotencyKey)
                    .build(), RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            return new CheckoutResult(s.getId(), s.getUrl());
        } catch (StripeException e) {
            throw providerError(e);
        }
    }

    private static void requirePrice(String priceId) {
        if (priceId == null || priceId.isBlank()) {
            throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "This price has no Stripe Price id configured yet (Admin → Prices)");
        }
    }

    private static ApiException providerError(StripeException e) {
        return new ApiException(ErrorCode.PROVIDER_ERROR, "Payment provider error: " + e.getUserMessage(),
                java.util.Map.of("providerCode", String.valueOf(e.getCode())));
    }
}
