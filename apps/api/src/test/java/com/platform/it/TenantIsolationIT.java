package com.platform.it;

import com.platform.support.Fixtures;
import com.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Acceptance test H: two establishments can never see or modify each other's data. */
class TenantIsolationIT extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Fixtures fx;

    @Test
    void crossTenantAccessIsDenied() throws Exception {
        var a = fx.brazil();
        var b = fx.tenant("US", "US_EIN", "123456789");
        UUID contactOfB = fx.contact(b.orgId(), "secret-b@example.com", null, false);

        // A's token with B's organization header → forbidden (membership checked on every request)
        for (String path : new String[]{"/api/v1/contacts", "/api/v1/automations", "/api/v1/campaigns", "/api/v1/channels", "/api/v1/billing/invoices",
                "/api/v1/messages", "/api/v1/conversations", "/api/v1/organizations/current", "/api/v1/integrations/api-keys"}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + a.token()).header("X-Organization-Id", b.orgId().toString()))
                    .andExpect(status().isForbidden());
        }
        // A's own org header, B's resource id → not found (queries are scoped by organization)
        mvc.perform(get("/api/v1/contacts/" + contactOfB).header("Authorization", "Bearer " + a.token()).header("X-Organization-Id", a.orgId().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/contacts/" + contactOfB).header("Authorization", "Bearer " + a.token()).header("X-Organization-Id", a.orgId().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijack\",\"email\":\"x@example.com\"}"))
                .andExpect(status().isNotFound());
        // A's listing never contains B's contact
        mvc.perform(get("/api/v1/contacts?q=secret-b").header("Authorization", "Bearer " + a.token()).header("X-Organization-Id", a.orgId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        // No token at all → 401
        mvc.perform(get("/api/v1/contacts").header("X-Organization-Id", a.orgId().toString())).andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyRoleCannotWrite() throws Exception {
        var a = fx.brazil();
        var reader = fx.tenant("PT", "EU_VAT", "PT123456789");
        // add reader's user to org A as READ_ONLY
        org.springframework.jdbc.core.JdbcTemplate jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        jdbc.update("INSERT INTO organization_members (id, organization_id, user_id, role) VALUES (?,?,?, 'READ_ONLY')", UUID.randomUUID(), a.orgId(), reader.userId());
        mvc.perform(get("/api/v1/contacts").header("Authorization", "Bearer " + reader.token()).header("X-Organization-Id", a.orgId().toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/contacts").header("Authorization", "Bearer " + reader.token()).header("X-Organization-Id", a.orgId().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"n@example.com\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/billing/overview").header("Authorization", "Bearer " + reader.token()).header("X-Organization-Id", a.orgId().toString()))
                .andExpect(status().isForbidden());
    }

    @Autowired org.springframework.context.ApplicationContext ctx;
}
