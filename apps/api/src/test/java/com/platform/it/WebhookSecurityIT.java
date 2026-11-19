package com.platform.it;

import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Billing via provider webhooks: signature, replay protection, idempotency, out-of-order, state machine. */
class WebhookSecurityIT extends IntegrationTest {
    static final String SECRET = "whsec_test_only_secret";
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;

    String sign(String payload, long ts) throws Exception {
        return "t=" + ts + ",v1=" + Webhook.Util.computeHmacSha256(SECRET, ts + "." + payload);
    }

    void send(String payload) throws Exception {
        long ts = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload).header("Stripe-Signature", sign(payload, ts)))
                .andExpect(status().isOk());
    }

    @Test
    void forgedOrReplayedWebhooksAreRejected() throws Exception {
        String payload = "{\"id\":\"evt_forged\",\"type\":\"invoice.paid\",\"data\":{\"object\":{}}}";
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload).header("Stripe-Signature", "t=1,v1=deadbeef"))
                .andExpect(status().isUnauthorized());
        long old = Instant.now().minusSeconds(3600).getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload).header("Stripe-Signature", sign(payload, old)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content("{}").header("X-Hub-Signature-256", "sha256=00"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=x")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=verify-me&hub.challenge=abc")).andExpect(content().string("abc"));
    }

    @Test
    void invoiceBillingActivatesOnlyAfterPaymentAndIsIdempotent() throws Exception {
        var t = fx.tenant("DE", "EU_VAT", "DE123456789");
        String customer = "cus_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO billing_customers (organization_id, provider, provider_customer_id) VALUES (?, 'STRIPE', ?)", t.orgId(), customer);
        UUID subId = UUID.randomUUID();
        String stripeSub = "sub_" + subId.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO subscriptions (id, organization_id, plan_id, price_id, status, collection_method, provider_subscription_id)
                SELECT ?, ?, p.id, pr.id, 'PAYMENT_PENDING', 'SEND_INVOICE', ? FROM plans p JOIN prices pr ON pr.plan_id = p.id WHERE p.code = 'EMAIL_STARTER'""",
                subId, t.orgId(), stripeSub);
        String invoice = """
                {"id":"%s","type":"%s","data":{"object":{"id":"in_%s","object":"invoice","customer":"%s","status":"%s","currency":"eur","subtotal":1000,"total":1000,
                 "amount_paid":%d,"amount_due":1000,"collection_method":"send_invoice","number":"INV-1","due_date":%d,
                 "status_transitions":{"paid_at":%s},"total_taxes":[{"amount":0,"taxability_reason":"reverse_charge"}],
                 "parent":{"subscription_details":{"subscription":"%s","metadata":{"subscription_id":"%s"}}},
                 "lines":{"data":[{"period":{"start":1763000000,"end":1765600000}}]}}}}""";
        long due = Instant.now().plusSeconds(864000).getEpochSecond();
        send(invoice.formatted("evt_fin_" + subId, "invoice.finalized", subId, customer, "open", 0, due, "null", stripeSub, subId));
        assertThat(subStatus(subId)).isEqualTo("PAYMENT_PENDING"); // issued invoice ≠ payment
        String paid = invoice.formatted("evt_paid_" + subId, "invoice.paid", subId, customer, "paid", 1000, due, Instant.now().getEpochSecond(), stripeSub, subId);
        send(paid);
        send(paid); // duplicate delivery
        assertThat(subStatus(subId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE organization_id = ?", Integer.class, t.orgId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription_events WHERE subscription_id = ? AND to_status = 'ACTIVE'", Integer.class, subId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reverse_charge FROM invoices WHERE organization_id = ?", Boolean.class, t.orgId())).isTrue();
        // Failed renewal → PAST_DUE; then paid → ACTIVE again
        send(invoice.formatted("evt_fail_" + subId, "invoice.payment_failed", subId + "x", customer, "open", 0, due, "null", stripeSub, subId));
        assertThat(subStatus(subId)).isEqualTo("PAST_DUE");
        send(invoice.formatted("evt_paid2_" + subId, "invoice.paid", subId + "x", customer, "paid", 1000, due, Instant.now().getEpochSecond(), stripeSub, subId));
        assertThat(subStatus(subId)).isEqualTo("ACTIVE");
    }

    @Test
    void checkoutCompletedActivatesCardSubscription() throws Exception {
        var t = fx.brazil();
        UUID subId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO subscriptions (id, organization_id, plan_id, price_id, status, collection_method)
                SELECT ?, ?, p.id, pr.id, 'CHECKOUT_PENDING', 'CHARGE_AUTOMATICALLY' FROM plans p JOIN prices pr ON pr.plan_id = p.id WHERE p.code = 'EMAIL_STARTER'""",
                subId, t.orgId());
        send("""
                {"id":"evt_cs_%s","type":"checkout.session.completed","data":{"object":{"id":"cs_1","object":"checkout.session","client_reference_id":"%s",
                 "subscription":"sub_cs_%s","payment_status":"paid","metadata":{}}}}""".formatted(subId, subId, subId));
        assertThat(subStatus(subId)).isEqualTo("ACTIVE");
    }

    String subStatus(UUID subId) { return jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, subId); }
}
