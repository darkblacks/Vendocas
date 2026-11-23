package com.platform.taxation;

import com.platform.billing.StripeEventProcessor;
import com.platform.shared.outbox.OutboxEvent;
import com.platform.shared.outbox.OutboxHandler;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Fiscal side of billing events. Runs in its own transaction after the payment is committed (outbox), at most once
 * per event; a failure here is retried by the outbox and never affects payments or subscriptions.
 */
@Component
public class FiscalEventHandler implements OutboxHandler {
    private final FiscalDocuments documents;

    public FiscalEventHandler(FiscalDocuments documents) { this.documents = documents; }

    @Override public String consumer() { return "fiscal"; }

    @Override public Set<String> eventTypes() { return Set.of(StripeEventProcessor.EVENT_INVOICE_PAID, StripeEventProcessor.EVENT_PAYMENT_REFUNDED); }

    @Override
    public void handle(OutboxEvent e) {
        switch (e.eventType()) {
            case StripeEventProcessor.EVENT_INVOICE_PAID -> documents.register(e.organizationId(),
                    UUID.fromString(e.payload().path("invoiceId").asString()), UUID.fromString(e.payload().path("paymentId").asString()));
            case StripeEventProcessor.EVENT_PAYMENT_REFUNDED -> {
                String inv = e.payload().path("invoiceId").asString(null);
                documents.paymentRefunded(inv == null ? null : UUID.fromString(inv), e.payload().path("full").asBoolean(false));
            }
            default -> { }
        }
    }
}
