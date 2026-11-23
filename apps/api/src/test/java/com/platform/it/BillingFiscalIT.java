package com.platform.it;

import com.platform.billing.PaymentProvider;
import com.platform.operations.PlatformSettings.Environment;
import com.platform.operations.PlatformSettings.FiscalProvider;
import com.platform.operations.PlatformSettings.StripeMode;
import com.platform.operations.PlatformSettingsService;
import com.platform.operations.StripeKeyMode;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.jobs.JobWorker;
import com.platform.shared.outbox.OutboxRelay;
import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import com.platform.support.TestProviders;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment × Stripe mode × fiscal automation × fiscal provider are independent: Billing (payments, subscriptions,
 * renewals, refunds) behaves identically in every combination, and the fiscal module only calls the provider when
 * automatic issuance is on with a selected, configured provider. Brazilian seller so NFS-e obligations apply.
 */
@TestPropertySource(properties = {
        "app.seller.country-code=BR", "app.seller.tax-id=11222333000181", "app.seller.municipal-registration=123",
        "app.seller.municipality-code=3550308", "app.seller.service-code=0107", "app.seller.legal-name=Vendocas LTDA",
        "app.stripe.secret-key=sk_live_test_fixture_not_a_real_key",
        "CONTROL_STATUS_TOKEN=status-token-0123456789abcdefghijklmnop", "CONTROL_ADMIN_TOKEN=admin-token-0123456789abcdefghijklmnopq"})
class BillingFiscalIT extends IntegrationTest {
    static final String SECRET = "whsec_test_only_secret";
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformSettingsService settings;
    @Autowired OutboxRelay outbox;
    @Autowired JobWorker worker;
    @Autowired PaymentProvider stripe;

    @BeforeEach void reset() { TestProviders.FISCAL.reset(); }

    @AfterEach void restoreDefaults() {
        jdbc.update("UPDATE platform_settings SET environment='HOMOLOGATION', stripe_mode='TEST', fiscal_automation=false, fiscal_provider='NONE' WHERE id = 1");
        TestProviders.FISCAL.reset();
    }

    enum Fiscal { NONE, FOCUS_CONFIGURED, FOCUS_UNCONFIGURED }

    static Stream<Arguments> combinations() {
        List<Arguments> all = new ArrayList<>();
        for (Environment e : Environment.values())
            for (StripeMode m : StripeMode.values())
                for (boolean auto : new boolean[]{false, true})
                    for (Fiscal f : Fiscal.values()) all.add(Arguments.of(e, m, auto, f));
        return all.stream(); // 2 × 2 × 2 × 3 = 24
    }

    @ParameterizedTest(name = "{0} · Stripe {1} · automação {2} · {3}")
    @MethodSource("combinations")
    void billingNeverDependsOnFiscalAndFiscalFollowsTheSwitches(Environment env, StripeMode mode, boolean auto, Fiscal fiscal) throws Exception {
        configure(env, mode, auto, fiscal == Fiscal.NONE ? FiscalProvider.NONE : FiscalProvider.FOCUS_NFE);
        TestProviders.FISCAL.configured = fiscal == Fiscal.FOCUS_CONFIGURED;
        Paid p = payInvoice("BR", mode == StripeMode.LIVE);

        // Billing: identical in every combination.
        assertThat(subStatus(p.subId())).isEqualTo("ACTIVE");
        var pay = jdbc.queryForMap("SELECT status, environment, livemode FROM payments WHERE invoice_id = ?", p.invoiceId());
        assertThat(pay.get("status")).isEqualTo("SUCCEEDED");
        assertThat(pay.get("environment")).isEqualTo(env.name());
        assertThat(pay.get("livemode")).isEqualTo(mode == StripeMode.LIVE);

        // Fiscal: the obligation is always recorded; the provider is called only with automation + configured provider.
        String expected = !auto ? "AWAITING_MANUAL" : fiscal == Fiscal.FOCUS_CONFIGURED ? "ISSUED" : "NOT_CONFIGURED";
        assertThat(fiscalStatus(p.invoiceId())).isEqualTo(expected);
        assertThat(calls(p.invoiceId())).isEqualTo("ISSUED".equals(expected) ? 1 : 0);
    }

    @Test
    void nonBrazilianSalesHaveNoNfseObligationInAnyMode() throws Exception {
        configure(Environment.PRODUCTION, StripeMode.LIVE, true, FiscalProvider.FOCUS_NFE);
        Paid p = payInvoice("DE", true);
        assertThat(subStatus(p.subId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tax_documents WHERE invoice_id = ?", Integer.class, p.invoiceId())).isZero();
        assertThat(calls(p.invoiceId())).isZero();
    }

    @Test
    void fiscalProviderOutageNeverAffectsPayments() throws Exception {
        configure(Environment.PRODUCTION, StripeMode.LIVE, true, FiscalProvider.FOCUS_NFE);
        TestProviders.FISCAL.fail = true;
        Paid p = payInvoice("BR", true);
        assertThat(subStatus(p.subId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE invoice_id = ?", String.class, p.invoiceId())).isEqualTo("SUCCEEDED");
        assertThat(fiscalStatus(p.invoiceId())).isEqualTo("PENDING"); // retried later by the job worker
    }

    @Test
    void switchingAutomationOffAfterQueueingDoesNotCallTheProvider() throws Exception {
        configure(Environment.PRODUCTION, StripeMode.LIVE, true, FiscalProvider.FOCUS_NFE);
        Paid p = payInvoice("BR", true, false); // event delivered, job queued but not executed
        configure(Environment.PRODUCTION, StripeMode.LIVE, false, FiscalProvider.FOCUS_NFE);
        drainAll();
        assertThat(fiscalStatus(p.invoiceId())).isEqualTo("AWAITING_MANUAL");
        assertThat(calls(p.invoiceId())).isZero();
    }

    @Test
    void refundsAndRenewalsWorkWithFiscalOffAndFlagDocumentsForReview() throws Exception {
        configure(Environment.PRODUCTION, StripeMode.LIVE, false, FiscalProvider.NONE);
        Paid p = payInvoice("BR", true);
        String pi = jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE invoice_id = ?", String.class, p.invoiceId());
        send("""
                {"id":"evt_ref_%s","type":"charge.refunded","livemode":true,"data":{"object":{"id":"ch_1","object":"charge","livemode":true,
                 "payment_intent":"%s","amount":1000,"amount_refunded":1000}}}""".formatted(p.subId(), pi));
        drainAll();
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE invoice_id = ?", String.class, p.invoiceId())).isEqualTo("REFUNDED");
        var doc = jdbc.queryForMap("SELECT status, needs_review FROM tax_documents WHERE invoice_id = ?", p.invoiceId());
        assertThat(doc.get("status")).isEqualTo("AWAITING_MANUAL"); // never auto-canceled, never waived
        assertThat(doc.get("needs_review")).isEqualTo(true);
        assertThat(calls(p.invoiceId())).isZero();
        // a later renewal on the same subscription still activates normally
        Paid renewal = payInvoiceFor(p, "evt_ren_" + p.subId(), "in_ren_" + p.subId(), true, true);
        assertThat(subStatus(renewal.subId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE organization_id = (SELECT organization_id FROM subscriptions WHERE id = ?)",
                Integer.class, p.subId())).isEqualTo(2);
    }

    @Test
    void manualAndLaterIssuance() throws Exception {
        String admin = fx.platformAdmin();
        configure(Environment.PRODUCTION, StripeMode.LIVE, false, FiscalProvider.NONE);
        Paid a = payInvoice("BR", true);
        Paid b = payInvoice("BR", true);
        // No provider selected: "issue now" is refused; recording an externally issued document works.
        mvc.perform(post("/api/v1/admin/fiscal/invoices/{id}/issue", a.invoiceId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/fiscal/invoices/{id}/record-external", a.invoiceId()).header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"number\":\"2026/000123\",\"notes\":\"Emitida no portal da prefeitura\"}"))
                .andExpect(status().isOk());
        var doc = jdbc.queryForMap("SELECT status, provider, number FROM tax_documents WHERE invoice_id = ?", a.invoiceId());
        assertThat(doc).containsEntry("status", "ISSUED").containsEntry("provider", "MANUAL").containsEntry("number", "2026/000123");
        // Later: Focus NFe selected (automation still off) → backlog issued on demand.
        configure(Environment.PRODUCTION, StripeMode.LIVE, false, FiscalProvider.FOCUS_NFE);
        mvc.perform(post("/api/v1/admin/fiscal/invoices/{id}/issue", b.invoiceId()).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        drainAll();
        assertThat(fiscalStatus(b.invoiceId())).isEqualTo("ISSUED");
        assertThat(calls(b.invoiceId())).isEqualTo(1);
        assertThat(calls(a.invoiceId())).isZero();
        mvc.perform(get("/api/v1/admin/fiscal/documents?status=ISSUED").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").isNumber());
    }

    @Test
    void paymentsExportForReconciliation() throws Exception {
        String admin = fx.platformAdmin();
        configure(Environment.HOMOLOGATION, StripeMode.LIVE, false, FiscalProvider.NONE);
        Paid p = payInvoice("BR", true);
        String pi = jdbc.queryForObject("SELECT provider_payment_id FROM payments WHERE invoice_id = ?", String.class, p.invoiceId());
        mvc.perform(get("/api/v1/admin/billing/payments?format=csv").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment; filename=\"payments.csv\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(pi)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HOMOLOGATION")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AWAITING_MANUAL")));
        mvc.perform(get("/api/v1/admin/billing/payments?fiscalStatus=AWAITING_MANUAL").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.provider_payment_id == '" + pi + "')].livemode").value(true));
        // tenants cannot read platform data
        mvc.perform(get("/api/v1/admin/billing/payments").header("Authorization", "Bearer " + fx.brazil().token())).andExpect(status().isForbidden());
    }

    @Test
    void settingsApiIsIndependentVersionedAndAudited() throws Exception {
        String admin = fx.platformAdmin();
        int v = settings.current().version();
        mvc.perform(put("/api/v1/admin/platform-settings").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environment\":\"HOMOLOGATION\",\"stripeMode\":\"LIVE\",\"fiscalAutomation\":false,\"fiscalProvider\":\"NONE\",\"expectedVersion\":" + v + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settings.stripeMode").value("LIVE"))
                .andExpect(jsonPath("$.settings.environment").value("HOMOLOGATION"))
                .andExpect(jsonPath("$.stripeModeMatches").value(true))
                .andExpect(jsonPath("$.fiscalMode").value("MANUAL"))
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("cobranças são reais")));
        mvc.perform(put("/api/v1/admin/platform-settings").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fiscalAutomation\":true,\"expectedVersion\":" + v + "}")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'platform.settings.updated'", Integer.class)).isPositive();
        mvc.perform(put("/api/v1/admin/platform-settings").header("Authorization", "Bearer " + fx.brazil().token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stripeMode\":\"TEST\"}")).andExpect(status().isForbidden());
        // the key value never appears
        mvc.perform(get("/api/v1/admin/platform-settings").header("Authorization", "Bearer " + admin))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sk_live"))));
    }

    @Test
    void stripeKeyModeMustMatchTheSelectedModeBeforeAnyOutgoingCall() {
        assertThat(StripeKeyMode.of("sk_live_x")).isEqualTo(StripeKeyMode.Mode.LIVE);
        assertThat(StripeKeyMode.of("rk_test_x")).isEqualTo(StripeKeyMode.Mode.TEST);
        assertThat(StripeKeyMode.of("pk_live_x")).isEqualTo(StripeKeyMode.Mode.UNKNOWN);
        assertThat(StripeKeyMode.of(null)).isEqualTo(StripeKeyMode.Mode.NONE);
        configure(Environment.PRODUCTION, StripeMode.TEST, false, FiscalProvider.NONE); // live key configured, TEST selected
        assertThatThrownBy(() -> stripe.ensureCustomer(new PaymentProvider.CustomerInfo(UUID.randomUUID(), "X", "x@example.com", "BR",
                "Rua 1", null, "SP", "SP", "01000-000", "pt-BR", null, null), null))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.PROVIDER_MODE_MISMATCH);
        assertThat(settings.status().services()).anyMatch(s -> s.key().equals("stripe") && s.state().equals("BLOCKED_MODE_MISMATCH"));
        configure(Environment.HOMOLOGATION, StripeMode.LIVE, false, FiscalProvider.NONE); // aligned (homologation may use live)
        assertThat(settings.status().stripeModeMatches()).isTrue();
    }

    @Test
    void controlCenterReadsWithStatusTokenAndWritesOnlyWithAdminToken() throws Exception {
        mvc.perform(get("/internal/status").header("X-Control-Token", "status-token-0123456789abcdefghijklmnop"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.platform.settings.environment").isNotEmpty())
                .andExpect(jsonPath("$.settingsWritable").value(true));
        String body = "{\"environment\":\"PRODUCTION\",\"stripeMode\":\"LIVE\",\"fiscalAutomation\":true,\"fiscalProvider\":\"FOCUS_NFE\"}";
        mvc.perform(put("/internal/platform-settings").header("X-Control-Admin-Token", "status-token-0123456789abcdefghijklmnop")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/platform-settings").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/platform-settings").header("X-Control-Admin-Token", "admin-token-0123456789abcdefghijklmnopq")
                        .header("X-Control-Actor", "owner@example.com").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.settings.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.fiscalMode").value("AUTOMATIC"));
        assertThat(jdbc.queryForObject("SELECT updated_by FROM platform_settings", String.class)).isEqualTo("control-center:owner@example.com");
    }

    // ------------------------------------------------------------------ helpers

    record Paid(UUID subId, UUID invoiceId, String customer, String stripeSub) {}

    void configure(Environment e, StripeMode m, boolean auto, FiscalProvider p) {
        settings.update(new PlatformSettingsService.Update(e, m, auto, p, null), "test", "SYSTEM", null);
    }

    Paid payInvoice(String country, boolean livemode) throws Exception { return payInvoice(country, livemode, true); }

    Paid payInvoice(String country, boolean livemode, boolean runJobs) throws Exception {
        var t = "BR".equals(country) ? fx.brazil() : fx.tenant("DE", "EU_VAT", "DE123456789");
        String customer = "cus_" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO billing_customers (organization_id, provider, provider_customer_id) VALUES (?, 'STRIPE', ?)", t.orgId(), customer);
        UUID subId = UUID.randomUUID();
        String stripeSub = "sub_" + subId.toString().substring(0, 12);
        jdbc.update("""
                INSERT INTO subscriptions (id, organization_id, plan_id, price_id, status, collection_method, provider_subscription_id)
                SELECT ?, ?, p.id, pr.id, 'PAYMENT_PENDING', 'CHARGE_AUTOMATICALLY', ? FROM plans p JOIN prices pr ON pr.plan_id = p.id
                WHERE p.code = 'EMAIL_STARTER' AND pr.active LIMIT 1""", subId, t.orgId(), stripeSub);
        return payInvoiceFor(new Paid(subId, null, customer, stripeSub), "evt_paid_" + subId, "in_" + subId, livemode, runJobs);
    }

    Paid payInvoiceFor(Paid p, String eventId, String invoiceId, boolean livemode, boolean runJobs) throws Exception {
        String country = jdbc.queryForObject("""
                SELECT o.billing_country_code FROM subscriptions s JOIN organizations o ON o.id = s.organization_id WHERE s.id = ?""", String.class, p.subId());
        String currency = "BR".equals(country) ? "brl" : "eur";
        long now = Instant.now().getEpochSecond();
        send("""
                {"id":"%s","type":"invoice.paid","livemode":%s,"data":{"object":{"id":"%s","object":"invoice","livemode":%s,"customer":"%s","status":"paid",
                 "currency":"%s","subtotal":1000,"total":1000,"amount_paid":1000,"amount_due":1000,"collection_method":"charge_automatically",
                 "number":"VD-%s","payment_intent":"pi_%s","status_transitions":{"paid_at":%d},
                 "parent":{"subscription_details":{"subscription":"%s","metadata":{"subscription_id":"%s"}}},
                 "lines":{"data":[{"period":{"start":%d,"end":%d}}]}}}}""".formatted(eventId, livemode, invoiceId, livemode, p.customer(), currency,
                invoiceId.substring(Math.max(0, invoiceId.length() - 6)), invoiceId, now, p.stripeSub(), p.subId(), now, now + 2_592_000));
        if (runJobs) drainAll(); else outbox.drain(1000);
        UUID inv = jdbc.queryForObject("SELECT id FROM invoices WHERE provider_invoice_id = ?", UUID.class, invoiceId);
        return new Paid(p.subId(), inv, p.customer(), p.stripeSub());
    }

    void drainAll() {
        for (int i = 0; i < 10; i++) if (outbox.drain(1000) + worker.runOnce() == 0) break;
    }

    void send(String payload) throws Exception {
        long ts = Instant.now().getEpochSecond();
        mvc.perform(post("/api/v1/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", "t=" + ts + ",v1=" + Webhook.Util.computeHmacSha256(SECRET, ts + "." + payload))).andExpect(status().isOk());
    }

    /** Provider calls for this test's invoice only (the test database is shared with other suites). */
    long calls(UUID invoiceId) { return TestProviders.FISCAL.submitted.stream().filter(r -> r.equals("vd-" + invoiceId)).count(); }

    String subStatus(UUID id) { return jdbc.queryForObject("SELECT status FROM subscriptions WHERE id = ?", String.class, id); }

    String fiscalStatus(UUID invoiceId) {
        return jdbc.queryForObject("SELECT status FROM tax_documents WHERE invoice_id = ?", String.class, invoiceId);
    }
}
