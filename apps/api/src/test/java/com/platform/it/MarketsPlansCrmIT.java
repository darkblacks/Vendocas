package com.platform.it;

import com.platform.billing.Catalog;
import com.platform.commerce.CommerceService;
import com.platform.crm.CrmService;
import com.platform.messaging.MessageService;
import com.platform.messaging.SendRequest;
import com.platform.shared.Ids;
import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import com.platform.support.TestProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Markets, regional plans/prices/quotas (Control Center), alert-plan rules, CRM stages and the Sales Registry —
 * against a real PostgreSQL.
 */
@TestPropertySource(properties = "CONTROL_ADMIN_TOKEN=" + MarketsPlansCrmIT.ADMIN)
class MarketsPlansCrmIT extends IntegrationTest {
    static final String ADMIN = "test-admin-token-0123456789abcdefghijklmn";
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageService messages;
    @Autowired CommerceService commerce;
    @Autowired CrmService crm;
    @Autowired Catalog catalog;

    @BeforeEach
    void provider() { TestProviders.nextOutcome = com.platform.messaging.providers.MessageProvider.Outcome.ACCEPTED; }

    String market(UUID org) { return jdbc.queryForObject("SELECT market_code || ':' || billing_currency FROM organizations WHERE id = ?", String.class, org); }

    // ------------------------------------------------------------------ markets

    @Test
    void organizationsBelongToTheMarketOfTheirCountry() {
        assertThat(market(fx.brazil().orgId())).isEqualTo("BR:BRL");
        assertThat(market(fx.tenant("MX", "NONE", null).orgId())).isEqualTo("LATAM:USD");
        assertThat(market(fx.tenant("AR", "OTHER", "30-71234567-1").orgId())).isEqualTo("LATAM:USD");
        assertThat(market(fx.tenant("CA", "NONE", null).orgId())).isEqualTo("CA:USD");
        assertThat(market(fx.tenant("US", "NONE", null).orgId())).isEqualTo("US:USD");
        assertThat(market(fx.tenant("GB", "NONE", null).orgId())).isEqualTo("EU:EUR");
        assertThat(market(fx.tenant("GF", "NONE", null).orgId())).isEqualTo("EU:EUR");
        assertThat(market(fx.tenant("DE", "EU_VAT", "DE123456789").orgId())).isEqualTo("EU:EUR");
    }

    @Test
    void publicStorefrontShowsEachMarketsPrices() throws Exception {
        mvc.perform(get("/api/v1/public/markets")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'LATAM')].defaultLocale").value("es-419"))
                .andExpect(jsonPath("$[?(@.code == 'EU')].countries[*]").value(org.hamcrest.Matchers.hasItems("GB", "GF", "ES", "PT", "FR", "IE", "IT", "DE")));
        mvc.perform(get("/api/v1/public/markets/LATAM/plans")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("ALERTA")).andExpect(jsonPath("$[0].monthlyAmountMinor").value(1900))
                .andExpect(jsonPath("$[1].code").value("ALERTA_VENDAS")).andExpect(jsonPath("$[1].monthlyAmountMinor").value(2900))
                .andExpect(jsonPath("$[2].code").value("ILIMITADO")).andExpect(jsonPath("$[2].monthlyAmountMinor").value(11000))
                .andExpect(jsonPath("$[0].purchasable").value(false)); // no Stripe price linked yet
        mvc.perform(get("/api/v1/public/countries/PE")).andExpect(status().isOk()).andExpect(jsonPath("$.market").value("LATAM"));
    }

    @Test
    void controlCenterSetsMarketPricesAndQuotas() throws Exception {
        String run = Long.toString(System.nanoTime(), 36);
        mvc.perform(put("/internal/commercial/prices/EU/ALERTA").contentType(MediaType.APPLICATION_JSON).content("{\"unitAmountMinor\":1700}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/commercial/prices/EU/ALERTA").header("X-Control-Admin-Token", ADMIN).header("X-Control-Actor", "owner@example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitAmountMinor\":1700,\"stripePriceId\":\"price_euA1" + run + "\",\"offerText\":\"1er mois -50%\",\"trialDays\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("EUR")).andExpect(jsonPath("$.marketCode").value("EU"));
        // A new price replaces the old one; the old row stays (inactive) for existing subscriptions.
        mvc.perform(put("/internal/commercial/prices/EU/ALERTA").header("X-Control-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitAmountMinor\":1800,\"stripePriceId\":\"price_euA2" + run + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prices p JOIN plans pl ON pl.id = p.plan_id WHERE pl.code = 'ALERTA' AND p.market_code = 'EU' AND p.active",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prices p JOIN plans pl ON pl.id = p.plan_id WHERE pl.code = 'ALERTA' AND p.market_code = 'EU'",
                Integer.class)).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/api/v1/public/markets/EU/plans")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].monthlyAmountMinor").value(1800)).andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].purchasable").value(true));
        // A Stripe price can belong to only one plan × market.
        mvc.perform(put("/internal/commercial/prices/BR/ALERTA").header("X-Control-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitAmountMinor\":9900,\"stripePriceId\":\"price_euA2" + run + "\"}"))
                .andExpect(status().isConflict());

        mvc.perform(put("/internal/commercial/quotas/EU/ALERTA/WHATSAPP").header("X-Control-Admin-Token", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimit\":300}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].channel").value("WHATSAPP")).andExpect(jsonPath("$[0].monthlyLimit").value(300));
        mvc.perform(get("/internal/commercial/pricing").header("X-Control-Admin-Token", ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.market == 'EU')].plans[0].quotas[0].monthlyLimit").value(300));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action IN ('price.set','quota.set') AND details->>'actor' LIKE 'control-center:%'",
                Integer.class)).isGreaterThanOrEqualTo(3);

        mvc.perform(put("/internal/commercial/markets/CA").header("X-Control-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"channelOrder\":[\"EMAIL\",\"FAX\"]}")).andExpect(status().isBadRequest());
    }

    @Test
    void closedMarketRejectsSignupAndHidesStorefront() throws Exception {
        mvc.perform(put("/internal/commercial/markets/CA").header("X-Control-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        try {
            mvc.perform(get("/api/v1/public/markets/CA/plans")).andExpect(status().isNotFound());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> fx.tenant("CA", "NONE", null))
                    .hasMessageContaining("not open in this market");
        } finally {
            mvc.perform(put("/internal/commercial/markets/CA").header("X-Control-Admin-Token", ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":true}")).andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ alert plans

    UUID emailContact(UUID org, String email) {
        UUID c = fx.contact(org, email, null, true);
        jdbc.update("INSERT INTO contact_consents (id, organization_id, contact_id, channel, purpose, status, legal_basis, granted_at) VALUES (?,?,?, 'EMAIL', 'TRANSACTIONAL', 'GRANTED', 'CONTRACT', now())",
                Ids.newId(), org, c);
        return c;
    }

    SendRequest send(UUID org, UUID contact, String channel, String purpose, String origin) {
        return new SendRequest(org, contact, channel, purpose, null, "Seu pedido", "Oi! Obrigado pela compra.", null, origin, Ids.newId(),
                "it:" + UUID.randomUUID(), false, null, null);
    }

    @Test
    void alertPlanSendsOnlyAlertsWithTheAlertFooter() {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA");
        fx.emailChannel(t.orgId());
        jdbc.update("UPDATE organization_settings SET quiet_hours_start = '00:00', quiet_hours_end = '00:00', alert_contact = '(11) 98888-0000' WHERE organization_id = ?", t.orgId());
        UUID c = emailContact(t.orgId(), "alert-" + UUID.randomUUID() + "@example.com");
        jdbc.update("UPDATE contacts SET locale = 'pt-BR' WHERE id = ?", c);

        var campaign = messages.enqueue(send(t.orgId(), c, "EMAIL", "MARKETING", "CAMPAIGN"));
        assertThat(campaign.status()).isEqualTo("BLOCKED");
        assertThat(campaign.reason()).isEqualTo("PLAN_CAMPAIGNS_REQUIRED");
        var manual = messages.enqueue(send(t.orgId(), c, "EMAIL", "CUSTOMER_CARE", "MANUAL"));
        assertThat(manual.reason()).isEqualTo("PLAN_INBOX_REQUIRED");

        var alert = messages.enqueue(send(t.orgId(), c, "EMAIL", "TRANSACTIONAL", "AUTOMATION"));
        assertThat(alert.status()).isEqualTo("QUEUED");
        var row = jdbc.queryForMap("SELECT body, render_meta->>'html' AS html FROM messages WHERE id = ?", alert.messageId());
        assertThat((String) row.get("body")).contains("Esta mensagem é apenas um alerta. Para falar com a loja: (11) 98888-0000");
        assertThat((String) row.get("html")).contains("apenas um alerta");
    }

    @Test
    void unlimitedPlanAllowsCampaignsWithoutFooter() {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ILIMITADO");
        fx.emailChannel(t.orgId());
        jdbc.update("UPDATE organization_settings SET quiet_hours_start = '00:00', quiet_hours_end = '00:00' WHERE organization_id = ?", t.orgId());
        UUID c = emailContact(t.orgId(), "unl-" + UUID.randomUUID() + "@example.com");
        var r = messages.enqueue(send(t.orgId(), c, "EMAIL", "MARKETING", "CAMPAIGN"));
        assertThat(r.status()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id = ?", String.class, r.messageId())).doesNotContain("alert");
    }

    @Test
    void includedQuotaIsFreeUntilUsedUpThenBlocksWithoutExtraPack() {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA");
        jdbc.update("UPDATE organization_settings SET quiet_hours_start = '00:00', quiet_hours_end = '00:00' WHERE organization_id = ?", t.orgId());
        UUID conn = Ids.newId();
        jdbc.update("INSERT INTO channel_connections (id, organization_id, channel, provider, status) VALUES (?,?, 'SMS', 'FAKE', 'ACTIVE')", conn, t.orgId());
        jdbc.update("INSERT INTO sender_identities (id, organization_id, connection_id, kind, value, status, is_default) VALUES (?,?,?, 'SMS_NUMBER', '+5511900000000', 'ACTIVE', true)",
                Ids.newId(), t.orgId(), conn);
        catalog.setQuota("BR", "ALERTA", "SMS", 2L, null, false, "test");
        try {
            String[] results = new String[3];
            for (int i = 0; i < 3; i++) {
                UUID c = Ids.newId();
                jdbc.update("INSERT INTO contacts (id, organization_id, name, phone_e164, country_code, status, source) VALUES (?,?,?,?, 'BR', 'ACTIVE', 'MANUAL')",
                        c, t.orgId(), "Sms " + i, "+55119" + (10000000 + (int) (Math.random() * 89999999)));
                jdbc.update("INSERT INTO contact_consents (id, organization_id, contact_id, channel, purpose, status, legal_basis, granted_at) VALUES (?,?,?, 'SMS', 'TRANSACTIONAL', 'GRANTED', 'CONTRACT', now())",
                        Ids.newId(), t.orgId(), c);
                var r = messages.enqueue(send(t.orgId(), c, "SMS", "TRANSACTIONAL", "AUTOMATION"));
                results[i] = r.status() + ":" + r.reason() + ":" + r.costMinor();
            }
            // SMS has no usage rate for BRL: inside the quota it is included (free), after it it cannot be priced → blocked.
            assertThat(results[0]).isEqualTo("QUEUED:null:0");
            assertThat(results[1]).isEqualTo("QUEUED:null:0");
            assertThat(results[2]).isEqualTo("BLOCKED:PLAN_QUOTA_EXCEEDED:0");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE organization_id = ? AND quota_included", Integer.class, t.orgId())).isEqualTo(2);
        } finally {
            catalog.setQuota("BR", "ALERTA", "SMS", null, null, true, "test");
        }
    }

    // ------------------------------------------------------------------ CRM

    UUID buyer(UUID org, String name) {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO contacts (id, organization_id, name, email, country_code, status, source) VALUES (?,?,?,?, 'BR', 'ACTIVE', 'MANUAL')",
                id, org, name, name.toLowerCase().replace(' ', '.') + "-" + UUID.randomUUID() + "@example.com");
        return id;
    }

    void order(UUID org, UUID contact, long amount, int daysAgo) {
        commerce.upsertOrder(org, new CommerceService.OrderInput("o-" + UUID.randomUUID(), contact, "COMPLETED", amount, "BRL",
                Instant.now().minus(daysAgo, ChronoUnit.DAYS)), "API");
    }

    String stage(UUID contact) { return jdbc.queryForObject("SELECT stage FROM contact_metrics WHERE contact_id = ?", String.class, contact); }

    @Test
    void b2cStagesFollowEachCustomersOwnWindow() throws Exception {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA_VENDAS");
        UUID fresh = buyer(t.orgId(), "Novo"), loyal = buyer(t.orgId(), "Fiel"), window = buyer(t.orgId(), "Janela"), risk = buyer(t.orgId(), "Risco"),
                gone = buyer(t.orgId(), "Sumido"), visitor = buyer(t.orgId(), "Visitante");
        order(t.orgId(), fresh, 10000, 2);
        order(t.orgId(), loyal, 10000, 20);   // comes every 15 days, bought 5 days ago → first half of the window
        order(t.orgId(), loyal, 15000, 5);
        order(t.orgId(), window, 8000, 32);   // comes every 20 days, bought 12 days ago → second half: the moment to come back
        order(t.orgId(), window, 8000, 12);
        order(t.orgId(), risk, 8000, 60);     // one purchase, store window 30 days → past the window
        order(t.orgId(), gone, 5000, 120);    // past 3× the window
        crm.recompute(t.orgId());

        assertThat(stage(fresh)).isEqualTo("FIRST_SALE");
        assertThat(stage(loyal)).isEqualTo("RECURRING");
        assertThat(stage(window)).isEqualTo("IN_WINDOW");
        assertThat(stage(risk)).isEqualTo("CHURN_RISK");
        assertThat(stage(gone)).isEqualTo("CHURNED");
        assertThat(stage(visitor)).isEqualTo("VISITOR");
        assertThat(jdbc.queryForObject("SELECT window_days FROM contact_metrics WHERE contact_id = ?", java.math.BigDecimal.class, loyal)).isEqualByComparingTo("15");
        // First classifications are silent; second purchases moved Fiel (FIRST_SALE → RECURRING) and Janela (CHURN_RISK → IN_WINDOW).
        assertThat(jdbc.queryForList("SELECT contact_id FROM contact_events WHERE organization_id = ? AND type = 'STAGE_CHANGED'", UUID.class, t.orgId()))
                .containsExactlyInAnyOrder(loyal, window);

        order(t.orgId(), risk, 9000, 0); // comes back
        assertThat(stage(risk)).isEqualTo("RECURRING");
        var ev = jdbc.queryForMap("SELECT properties->>'from' AS f, properties->>'to' AS t FROM contact_events WHERE contact_id = ? AND type = 'STAGE_CHANGED'", risk);
        assertThat(ev.get("f")).isEqualTo("CHURN_RISK");
        assertThat(ev.get("t")).isEqualTo("RECURRING");

        String auth = "Bearer " + t.token();
        mvc.perform(get("/api/v1/crm/customers?stage=CHURNED").header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("Sumido"));
        mvc.perform(get("/api/v1/crm/kanban").header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5)).andExpect(jsonPath("$[0].stage").value("FIRST_SALE"))
                .andExpect(jsonPath("$[?(@.stage == 'RECURRING')].count").value(2));
        mvc.perform(get("/api/v1/crm/customers/" + loyal).header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.averageTicketMinor").value(12500)).andExpect(jsonPath("$.nextExpectedAt").isNotEmpty());
        mvc.perform(get("/api/v1/crm/summary").header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stages.CHURNED").value(1)).andExpect(jsonPath("$.atRisk").value(0));

        mvc.perform(get("/api/v1/crm/insights/weekdays").header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));
        mvc.perform(get("/api/v1/crm/customers?sort=ticket&dir=asc&minOrders=2").header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items[0].name").value("Janela"));
        mvc.perform(get("/api/v1/crm/insights/replies").header("Authorization", auth).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk());
        // The kanban (and churn views) are part of the R$ 29,90 plan, not the alert plan.
        var alertOnly = fx.brazil();
        fx.activeSubscription(alertOnly.orgId(), "ALERTA");
        mvc.perform(get("/api/v1/crm/kanban").header("Authorization", "Bearer " + alertOnly.token()).header("X-Organization-Id", alertOnly.orgId().toString()))
                .andExpect(status().isPaymentRequired());
    }

    // ------------------------------------------------------------------ Sales Registry

    @Test
    void salesRegistryNeedsThePlanAndTurnsSalesIntoPurchases() throws Exception {
        var alertOnly = fx.brazil();
        fx.activeSubscription(alertOnly.orgId(), "ALERTA");
        String sale = """
                {"customer":{"name":"Ana Ribeiro","phone":"(21) 99876-1234"},"paymentMethod":"PIX","salesChannel":"STORE","alertConsents":["WHATSAPP"],
                 "items":[%s{"description":"Meia de lã","quantity":2,"unitAmountMinor":3900}]}""";
        // Alert plan: items/products need the catalogue (R$ 29,90)…
        mvc.perform(post("/api/v1/sales").header("Authorization", "Bearer " + alertOnly.token()).header("X-Organization-Id", alertOnly.orgId().toString())
                .contentType(MediaType.APPLICATION_JSON).content(sale.formatted(""))).andExpect(status().isPaymentRequired());
        // …but the "venda seca" (customer + amount paid) works on every plan.
        mvc.perform(post("/api/v1/sales").header("Authorization", "Bearer " + alertOnly.token()).header("X-Organization-Id", alertOnly.orgId().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"customer":{"name":"Seca","phone":"(21) 99876-0000"},"amountMinor":15990,"paymentMethod":"CASH","salesChannel":"STORE"}"""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(15990));
        mvc.perform(get("/api/v1/products").header("Authorization", "Bearer " + alertOnly.token()).header("X-Organization-Id", alertOnly.orgId().toString()))
                .andExpect(status().isPaymentRequired());

        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA_VENDAS");
        String auth = "Bearer " + t.token(), org = t.orgId().toString();
        String product = mvc.perform(post("/api/v1/products").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bota Couro Café 37\",\"sku\":\"BOT-37\",\"priceMinor\":28900,\"currency\":\"BRL\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        // The client cannot override a catalogue price: unitAmountMinor on a product line is ignored.
        mvc.perform(post("/api/v1/sales").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                        .content(sale.formatted("{\"productId\":\"" + product + "\",\"quantity\":1,\"unitAmountMinor\":1},")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(28900 + 2 * 3900)).andExpect(jsonPath("$.currency").value("BRL"));

        UUID contact = jdbc.queryForObject("SELECT id FROM contacts WHERE organization_id = ? AND phone_e164 = '+5521998761234'", UUID.class, t.orgId());
        assertThat(stage(contact)).isEqualTo("FIRST_SALE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.contact_id = ?", Integer.class, contact)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_consents WHERE contact_id = ? AND channel = 'WHATSAPP' AND status = 'GRANTED'", Integer.class, contact))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contact_events WHERE contact_id = ? AND type = 'PURCHASE_COMPLETED'", Integer.class, contact)).isEqualTo(1);

        mvc.perform(get("/api/v1/sales/summary").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sales").value(1)).andExpect(jsonPath("$.totalMinor").value(36700))
                .andExpect(jsonPath("$.newCustomers").value(1)).andExpect(jsonPath("$.byPayment.PIX").value(36700));
        mvc.perform(get("/api/v1/sales").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].items").value(3)).andExpect(jsonPath("$[0].contactName").value("Ana Ribeiro"));
    }

    @Test
    void b2bDealsMoveThroughTheFunnel() throws Exception {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA_VENDAS");
        String auth = "Bearer " + t.token(), org = t.orgId().toString();
        String a = id(mvc.perform(post("/api/v1/crm/deals").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Uniformes 2027\",\"companyName\":\"Escola Sol\",\"valueMinor\":1200000,\"currency\":\"BRL\"}")).andExpect(status().isOk()));
        String b = id(mvc.perform(post("/api/v1/crm/deals").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Revenda Sul\",\"valueMinor\":500000,\"currency\":\"BRL\"}")).andExpect(status().isOk()));
        mvc.perform(post("/api/v1/crm/deals/" + b + "/move").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"PROPOSAL\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PROPOSAL"));
        mvc.perform(post("/api/v1/crm/deals/" + a + "/move").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"PROPOSAL\",\"index\":0}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/crm/deals").header("Authorization", auth).header("X-Organization-Id", org)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].stage").value("PROPOSAL")).andExpect(jsonPath("$[1].count").value(2)).andExpect(jsonPath("$[1].valueMinor").value(1700000))
                .andExpect(jsonPath("$[1].deals[0].title").value("Uniformes 2027"));
        mvc.perform(post("/api/v1/crm/deals/" + b + "/move").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stage\":\"LOST\",\"lostReason\":\"Preço\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").isNotEmpty()).andExpect(jsonPath("$.lostReason").value("Preço"));
        var other = fx.brazil();
        fx.activeSubscription(other.orgId(), "ALERTA_VENDAS");
        mvc.perform(post("/api/v1/crm/deals/" + a + "/move").header("Authorization", "Bearer " + other.token()).header("X-Organization-Id", other.orgId().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"stage\":\"WON\"}")).andExpect(status().isNotFound());
    }

    String id(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString().replaceAll("^\\{\"id\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void personasLinkCustomersAndProductsAndShowBehaviour() throws Exception {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA_VENDAS");
        String auth = "Bearer " + t.token(), org = t.orgId().toString();
        String product = mvc.perform(post("/api/v1/products").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tênis escolar\",\"priceMinor\":19900,\"currency\":\"BRL\"}")).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String persona = id(mvc.perform(post("/api/v1/crm/personas").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mãe prática\",\"productIds\":[\"" + product + "\"]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].name").value("Tênis escolar")));
        UUID c1 = buyer(t.orgId(), "Mae Um"), c2 = buyer(t.orgId(), "Mae Dois");
        order(t.orgId(), c1, 20000, 3);
        order(t.orgId(), c2, 10000, 10);
        mvc.perform(post("/api/v1/crm/personas/" + persona + "/customers").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactIds\":[\"" + c1 + "\",\"" + c2 + "\"]}")).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(2));
        mvc.perform(get("/api/v1/crm/personas").header("Authorization", auth).header("X-Organization-Id", org)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customers").value(2)).andExpect(jsonPath("$[0].averageTicketMinor").value(15000))
                .andExpect(jsonPath("$[0].topWeekday").isNotEmpty());
        mvc.perform(get("/api/v1/crm/customers?persona=" + persona).header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].personas[0]").value("Mãe prática"));
        mvc.perform(post("/api/v1/crm/personas").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mãe prática\"}")).andExpect(status().isConflict());
        var alertOnly = fx.brazil();
        fx.activeSubscription(alertOnly.orgId(), "ALERTA");
        mvc.perform(get("/api/v1/crm/personas").header("Authorization", "Bearer " + alertOnly.token()).header("X-Organization-Id", alertOnly.orgId().toString()))
                .andExpect(status().isPaymentRequired());
    }

    @Test
    void onboardingCannotBeSkipped() throws Exception {
        var t = fx.brazil();
        String auth = "Bearer " + t.token(), org = t.orgId().toString();
        mvc.perform(get("/api/v1/onboarding").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(false));
        mvc.perform(post("/api/v1/onboarding/complete").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.whatsappChoice").value("required"));
        mvc.perform(put("/api/v1/onboarding").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeName\":\"Transmassa\",\"businessSegment\":\"Calçados\",\"alertContact\":\"(11) 98888-0000\",\"whatsappChoice\":\"CONNECT_OWN\",\"emailChoice\":\"OWN_ADDRESS\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/onboarding/complete").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.emailAddress").value("required"));
        mvc.perform(put("/api/v1/onboarding").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailChoice\":\"VENDOCAS_ADDRESS\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/onboarding/complete").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true)).andExpect(jsonPath("$.storeName").value("Transmassa"));
        assertThat(jdbc.queryForObject("SELECT alert_contact FROM organization_settings WHERE organization_id = ?", String.class, t.orgId())).isEqualTo("(11) 98888-0000");
    }

    @Test
    void storeTellsWhetherANumberHasWhatsapp() throws Exception {
        var t = fx.brazil();
        String auth = "Bearer " + t.token(), org = t.orgId().toString();
        UUID c = buyer(t.orgId(), "Zap");
        mvc.perform(put("/api/v1/crm/customers/" + c + "/whatsapp").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"YES\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/crm/customers?whatsapp=YES").header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].whatsappStatus").value("YES"));
        mvc.perform(put("/api/v1/crm/customers/" + c + "/whatsapp").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"MAYBE\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void contactsCanBeCreatedWithOnlyPhoneOrOnlyEmail() throws Exception {
        var t = fx.brazil();
        String auth = "Bearer " + t.token(), org = t.orgId().toString();
        mvc.perform(post("/api/v1/contacts").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Só telefone\",\"phone\":\"+5511977770002\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/contacts").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Só e-mail\",\"email\":\"so-email@example.com\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/contacts").header("Authorization", auth).header("X-Organization-Id", org).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Duplicado\",\"phone\":\"+5511977770002\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingContactId").isNotEmpty());
    }

    // ------------------------------------------------------------------ Control: merchants

    @Test
    void merchantsListAndCardForTheOperator() throws Exception {
        var t = fx.tenant("MX", "NONE", null);
        fx.activeSubscription(t.orgId(), "ILIMITADO");
        mvc.perform(get("/internal/merchants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/merchants?market=LATAM&plan=ILIMITADO").header("X-Control-Admin-Token", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '" + t.orgId() + "')].market").value("LATAM"))
                .andExpect(jsonPath("$.items[?(@.id == '" + t.orgId() + "')].health[*]").value(org.hamcrest.Matchers.hasItem("NO_WHATSAPP")));
        mvc.perform(get("/internal/merchants/" + t.orgId()).header("X-Control-Admin-Token", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.merchant.planCode").value("ILIMITADO"))
                .andExpect(jsonPath("$.quotas.length()").value(3));
        mvc.perform(get("/api/v1/billing/usage").header("Authorization", "Bearer " + t.token()).header("X-Organization-Id", t.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.market").value("LATAM")).andExpect(jsonPath("$.planCode").value("ILIMITADO"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prices WHERE market_code = 'LATAM' AND active", Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(Timestamp.from(Instant.now())).isNotNull();
    }
}
