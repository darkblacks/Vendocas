package com.platform.it;

import com.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Control Center status endpoint: shared-secret protected, no secrets in the body, real DB checks. */
@TestPropertySource(properties = "CONTROL_STATUS_TOKEN=test-status-token-0123456789abcdefghij")
class InternalStatusIT extends IntegrationTest {
    static final String TOKEN = "test-status-token-0123456789abcdefghij";
    @Autowired MockMvc mvc;

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        mvc.perform(get("/internal/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/status").header("X-Control-Token", TOKEN + "x")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/status").header("X-Control-Token", TOKEN)).andExpect(status().is4xxClientError());
        mvc.perform(get("/internal/other").header("X-Control-Token", TOKEN)).andExpect(status().is4xxClientError());
    }

    @Test
    void reportsDatabaseMigrationsAndWorkers() throws Exception {
        mvc.perform(get("/internal/status").header("X-Control-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.database.up").value(true))
                .andExpect(jsonPath("$.database.flywayVersion").isNotEmpty())
                .andExpect(jsonPath("$.workers.jobsDead").isNumber())
                .andExpect(jsonPath("$.redis.up").isBoolean())
                .andExpect(jsonPath("$.recentErrors").isArray());
    }
}
