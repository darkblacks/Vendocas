package com.platform.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests run against a real PostgreSQL: a Testcontainers instance (CI, any machine with
 * Docker) or, when TEST_DB_URL is set, an existing database (e.g. a local server without Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestProviders.class)
public abstract class IntegrationTest {
    static PostgreSQLContainer POSTGRES;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "vendocas"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "vendocas"));
            r.add("spring.flyway.clean-disabled", () -> "false");
            return;
        }
        if (POSTGRES == null) {
            POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
            POSTGRES.start();
        }
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
