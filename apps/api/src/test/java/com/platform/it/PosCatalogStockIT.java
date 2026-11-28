package com.platform.it;

import com.platform.shared.Ids;
import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import com.platform.support.TestProviders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Vendocas PDV on the paid plan: categories, products with photo, stock ledger, discounts, cancellation and reports. */
class PosCatalogStockIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;
    @Autowired JdbcTemplate jdbc;

    String auth;
    String org;

    ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String json) throws Exception {
        b.header("Authorization", auth).header("X-Organization-Id", org);
        if (json != null) b.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(b);
    }

    static String id(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
    }

    static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    @Test
    void catalogueStockSalesAndCancellation() throws Exception {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA_VENDAS");
        auth = "Bearer " + t.token();
        org = t.orgId().toString();

        // categories
        String drinks = id(call(post("/api/v1/product-categories"), "{\"name\":\"Bebidas\"}").andExpect(status().isOk()));
        call(post("/api/v1/product-categories"), "{\"name\":\"bebidas\"}").andExpect(status().isConflict());

        // product with stock control, cost, barcode and initial stock of 10
        String beer = id(call(post("/api/v1/products"), """
                {"name":"Cerveja long neck","priceMinor":1000,"currency":"BRL","categoryId":"%s","costMinor":400,"barcode":"7891234567895",
                 "unit":"UN","trackStock":true,"minStock":3,"initialStock":10}""".formatted(drinks)).andExpect(status().isOk()));
        // older clients still send the category as text: it is found or created
        String pizza = id(call(post("/api/v1/products"), "{\"name\":\"Pizza grande\",\"priceMinor\":5500,\"currency\":\"BRL\",\"category\":\"Pizzas\"}")
                .andExpect(status().isOk()));
        call(post("/api/v1/products"), "{\"name\":\"Outra\",\"priceMinor\":1,\"currency\":\"BRL\",\"barcode\":\"7891234567895\"}").andExpect(status().isConflict());
        call(get("/api/v1/products?q=7891234567895"), null).andExpect(status().isOk())   // scanner search by barcode
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].stockQuantity").value(10)).andExpect(jsonPath("$[0].category").value("Bebidas"));
        call(get("/api/v1/product-categories"), null).andExpect(jsonPath("$.length()").value(2));

        // photo: stored under the store's own folder in the bucket; replacing deletes the previous file
        var file = new MockMultipartFile("file", "cerveja.png", "image/png", png());
        mvc.perform(multipart("/api/v1/products/" + beer + "/photo").file(file).header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photoUrl").value(startsWith("https://bucket.test/org/" + org + "/products/")));
        String firstKey = jdbc.queryForObject("SELECT m.object_key FROM products p JOIN media_assets m ON m.id = p.photo_id WHERE p.id = ?::uuid", String.class, beer);
        assertThat(firstKey).startsWith("org/" + org + "/products/");
        assertThat(TestProviders.BUCKET).containsKey(firstKey);
        mvc.perform(multipart("/api/v1/products/" + beer + "/photo").file(file).header("Authorization", auth).header("X-Organization-Id", org)).andExpect(status().isOk());
        assertThat(TestProviders.BUCKET).doesNotContainKey(firstKey);
        var notImage = new MockMultipartFile("file", "x.png", "image/png", "%PDF-1.4 fake".getBytes());
        mvc.perform(multipart("/api/v1/products/" + beer + "/photo").file(notImage).header("Authorization", auth).header("X-Organization-Id", org))
                .andExpect(status().isBadRequest());

        // stock: purchase of 12 at a new cost, a loss of 1, then an inventory count of 20
        call(post("/api/v1/products/" + beer + "/stock"), "{\"kind\":\"PURCHASE\",\"quantity\":12,\"unitCostMinor\":450}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.stockQuantity").value(22)).andExpect(jsonPath("$.costMinor").value(450));
        call(post("/api/v1/products/" + beer + "/stock"), "{\"kind\":\"LOSS\",\"quantity\":1,\"reason\":\"Garrafa quebrada\"}")
                .andExpect(jsonPath("$.stockQuantity").value(21));
        call(post("/api/v1/products/" + beer + "/stock"), "{\"kind\":\"COUNT\",\"quantity\":20}").andExpect(jsonPath("$.stockQuantity").value(20));
        call(post("/api/v1/products/" + pizza + "/stock"), "{\"kind\":\"PURCHASE\",\"quantity\":1}").andExpect(status().isBadRequest()); // no stock control

        // sale: 18 beers + 1 pizza with R$ 10 discount → stock 2 (below the minimum of 3)
        String sale = (call(post("/api/v1/sales"), """
                {"customer":{"name":"Bruno","phone":"(11) 91234-0000"},"paymentMethod":"PIX","salesChannel":"STORE","discountMinor":1000,
                 "items":[{"productId":"%s","quantity":18},{"productId":"%s","quantity":1}]}""".formatted(beer, pizza))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalMinor").value(18 * 1000 + 5500 - 1000)).andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1"));
        call(get("/api/v1/products/" + beer), null).andExpect(jsonPath("$.stockQuantity").value(2)).andExpect(jsonPath("$.lowStock").value(true));
        call(get("/api/v1/products?lowStock=true"), null).andExpect(jsonPath("$.length()").value(1));
        call(post("/api/v1/sales"), """
                {"amountMinor":100,"discountMinor":10,"customer":{"phone":"(11) 91234-0000"},"paymentMethod":"PIX","salesChannel":"STORE"}""")
                .andExpect(status().isBadRequest()); // a discount needs items
        call(get("/api/v1/stock/movements?productId=" + beer), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("SALE")).andExpect(jsonPath("$[0].quantity").value(-18)).andExpect(jsonPath("$[0].balanceAfter").value(2))
                .andExpect(jsonPath("$.length()").value(5));
        call(get("/api/v1/stock/summary"), null).andExpect(jsonPath("$.trackedProducts").value(1)).andExpect(jsonPath("$.units").value(2))
                .andExpect(jsonPath("$.valueAtCostMinor").value(900)).andExpect(jsonPath("$.lowStock").value(1));
        call(get("/api/v1/sales/by-product"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Cerveja long neck")).andExpect(jsonPath("$[0].quantity").value(18))
                .andExpect(jsonPath("$[0].revenueMinor").value(18000)).andExpect(jsonPath("$[0].marginMinor").value(18000 - 18 * 450))
                .andExpect(jsonPath("$[1].marginMinor").doesNotExist()); // pizza has no cost
        call(get("/api/v1/sales/" + sale), null).andExpect(jsonPath("$.discountMinor").value(1000)).andExpect(jsonPath("$.items.length()").value(2));

        // an operator cannot cancel a sale
        var op = fx.brazil();
        jdbc.update("INSERT INTO organization_members (id, organization_id, user_id, role) VALUES (?,?,?, 'OPERATOR')", Ids.newId(), t.orgId(), op.userId());
        mvc.perform(post("/api/v1/sales/" + sale + "/cancel").header("Authorization", "Bearer " + op.token()).header("X-Organization-Id", org))
                .andExpect(status().isForbidden());

        // the manager cancels: units come back, the sale leaves the totals, the customer's value goes down
        UUID contact = jdbc.queryForObject("SELECT contact_id FROM orders WHERE id = ?::uuid", UUID.class, sale);
        call(post("/api/v1/sales/" + sale + "/cancel"), "{\"reason\":\"Cliente desistiu\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED")).andExpect(jsonPath("$.cancelReason").value("Cliente desistiu"))
                .andExpect(jsonPath("$.canceledBy").value("Test Owner"));
        call(post("/api/v1/sales/" + sale + "/cancel"), null).andExpect(status().isConflict());
        call(get("/api/v1/products/" + beer), null).andExpect(jsonPath("$.stockQuantity").value(20));
        call(get("/api/v1/sales/summary"), null).andExpect(jsonPath("$.sales").value(0));
        call(get("/api/v1/sales?status=CANCELED&q=bruno"), null).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT lifetime_value_minor FROM contacts WHERE id = ?", Long.class, contact)).isZero();

        // another store sees none of it
        var other = fx.brazil();
        fx.activeSubscription(other.orgId(), "ALERTA_VENDAS");
        mvc.perform(get("/api/v1/products/" + beer).header("Authorization", "Bearer " + other.token()).header("X-Organization-Id", other.orgId().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/products/" + beer + "/stock").header("Authorization", "Bearer " + other.token()).header("X-Organization-Id", other.orgId().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"LOSS\",\"quantity\":5}")).andExpect(status().isNotFound());
    }

    @Test
    void stockAndCatalogueNeedThePaidPlan() throws Exception {
        var t = fx.brazil();
        fx.activeSubscription(t.orgId(), "ALERTA");
        auth = "Bearer " + t.token();
        org = t.orgId().toString();
        call(get("/api/v1/product-categories"), null).andExpect(status().isPaymentRequired());
        call(get("/api/v1/stock/summary"), null).andExpect(status().isPaymentRequired());
    }
}
