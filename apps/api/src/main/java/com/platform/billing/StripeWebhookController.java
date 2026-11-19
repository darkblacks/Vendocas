package com.platform.billing;

import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stripe webhook endpoint. Signature (Stripe-Signature, HMAC-SHA256 with timestamp tolerance of
 * 300 s against replay) is verified before anything else; processing is idempotent per event id.
 */
@RestController
@Hidden
public class StripeWebhookController {
    private final AppProperties props;
    private final StripeEventProcessor processor;

    public StripeWebhookController(AppProperties props, StripeEventProcessor processor) {
        this.props = props;
        this.processor = processor;
    }

    @PostMapping(value = "/api/v1/webhooks/stripe", consumes = "application/json")
    public ResponseEntity<Void> receive(@RequestBody String payload, @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        String secret = props.stripe().webhookSecret();
        if (secret == null || secret.isBlank()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Stripe webhook secret not configured");
        try {
            Webhook.Signature.verifyHeader(payload, signature, secret, 300);
        } catch (SignatureVerificationException e) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid signature");
        }
        processor.process(payload);
        return ResponseEntity.ok().build();
    }
}
