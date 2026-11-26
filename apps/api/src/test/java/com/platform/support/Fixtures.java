package com.platform.support;

import com.platform.identity.User;
import com.platform.identity.UserRepository;
import com.platform.security.JwtService;
import com.platform.shared.Ids;
import com.platform.tenants.OrganizationService;
import com.platform.tenants.TenantDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Builds tenants for tests. Subscription activation here stands in for a provider-confirmed payment. */
@Component
public class Fixtures {
    private final UserRepository users;
    private final OrganizationService orgs;
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final TransactionTemplate tx;

    public Fixtures(UserRepository users, OrganizationService orgs, JdbcTemplate jdbc, JwtService jwt, TransactionTemplate tx) {
        this.users = users;
        this.orgs = orgs;
        this.jdbc = jdbc;
        this.jwt = jwt;
        this.tx = tx;
    }

    public record Tenant(UUID userId, UUID orgId, String token) {}

    public Tenant tenant(String country, String taxType, String taxId) {
        return tx.execute(s -> {
            User u = new User();
            u.setId(Ids.newId());
            u.setEmail("u" + UUID.randomUUID() + "@example.com");
            u.setFullName("Test Owner");
            u.setPasswordHash("x");
            u.setLocale("en");
            u.setTimeZone("UTC");
            u.setEmailVerifiedAt(Instant.now());
            u.setCreatedAt(Instant.now());
            u.setUpdatedAt(Instant.now());
            users.saveAndFlush(u);
            String zone = switch (country) { case "BR" -> "America/Sao_Paulo"; case "US", "CA" -> "America/New_York"; case "MX" -> "America/Mexico_City"; default -> "Europe/Lisbon"; };
            var org = orgs.create(u.getId(), new TenantDtos.CreateOrganizationRequest("Legal " + country, "Shop " + country, country, null, "en", zone,
                    "shop@example.com", null, null, "Street 1", null, "City", null, "00000",
                    new TenantDtos.TaxIdInput(taxType, taxId, "BUSINESS", null, null), true));
            return new Tenant(u.getId(), org.getId(), jwt.issueAccessToken(u.getId(), u.getEmail(), false, null));
        });
    }

    public Tenant brazil() { return tenant("BR", "BR_CNPJ", "11222333000181"); }

    /** Platform operator with MFA (token carries the mfa claim and the PLATFORM_ADMIN role). */
    public String platformAdmin() {
        Tenant t = tenant("DE", "EU_VAT", "DE123456789");
        jdbc.update("UPDATE users SET platform_role = 'PLATFORM_ADMIN', mfa_enabled_at = now() WHERE id = ?", t.userId());
        return jwt.issueAccessToken(t.userId(), "admin-" + t.userId() + "@example.com", true, "PLATFORM_ADMIN");
    }

    /** Marks a paid ACTIVE subscription on a plan (as the Stripe webhook would after invoice.paid). */
    public void activeSubscription(UUID orgId, String planCode) {
        jdbc.update("""
                INSERT INTO subscriptions (id, organization_id, plan_id, price_id, status, collection_method, current_period_start, current_period_end)
                SELECT ?, ?, p.id, pr.id, 'ACTIVE', 'CHARGE_AUTOMATICALLY', now(), now() + interval '1 month'
                FROM plans p JOIN prices pr ON pr.plan_id = p.id AND pr.currency = 'USD' AND pr.active WHERE p.code = ? LIMIT 1""", Ids.newId(), orgId, planCode);
    }

    /** Active email channel on the test provider. */
    public void emailChannel(UUID orgId) {
        UUID conn = Ids.newId();
        jdbc.update("INSERT INTO channel_connections (id, organization_id, channel, provider, status) VALUES (?,?, 'EMAIL', 'FAKE', 'ACTIVE')", conn, orgId);
        jdbc.update("INSERT INTO sender_identities (id, organization_id, connection_id, kind, value, status, is_default) VALUES (?,?,?, 'EMAIL_ADDRESS', ?, 'ACTIVE', true)",
                Ids.newId(), orgId, conn, "news-" + orgId.toString().substring(0, 8) + "@example.com");
    }

    public UUID contact(UUID orgId, String email, Instant lastPurchase, boolean marketingConsent) {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO contacts (id, organization_id, name, email, country_code, status, source, last_purchase_at) VALUES (?,?,?,?, 'BR', 'ACTIVE', 'MANUAL', ?)",
                id, orgId, "Contact " + email, email, lastPurchase == null ? null : Timestamp.from(lastPurchase));
        if (marketingConsent) {
            for (String purpose : new String[]{"MARKETING", "REACTIVATION"}) {
                jdbc.update("INSERT INTO contact_consents (id, organization_id, contact_id, channel, purpose, status, legal_basis, granted_at) VALUES (?,?,?, 'EMAIL', ?, 'GRANTED', 'CONSENT', now())",
                        Ids.newId(), orgId, id, purpose);
            }
        }
        return id;
    }
}
