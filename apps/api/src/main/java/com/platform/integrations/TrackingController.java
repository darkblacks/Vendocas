package com.platform.integrations;

import com.platform.commerce.CommerceService;
import com.platform.contacts.ContactEventService;
import com.platform.contacts.ContactRepository;
import com.platform.shared.Ids;
import com.platform.shared.crypto.SecretCipher;
import com.platform.shared.crypto.Tokens;
import com.platform.tenants.OrganizationRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Website tracking SDK endpoint. Anonymous visitors are NEVER identified by us: an anonymous id
 * becomes linked to a contact only through an identity token signed by the merchant's backend
 * (HMAC-SHA256 with the organization's tracking secret over "id|ts"). Events from unidentified
 * visitors, or without the visitor's tracking consent flag, are dropped. Client-side
 * purchase_completed stops abandonment flows but only counts as revenue when the order is signed.
 */
@RestController
@RequestMapping("/api/v1/track")
@Tag(name = "tracking")
public class TrackingController {
    static final Duration IDENTITY_MAX_AGE = Duration.ofHours(24);
    private final OrganizationRepository orgs;
    private final SecretCipher cipher;
    private final JdbcTemplate jdbc;
    private final ContactRepository contacts;
    private final ContactEventService events;
    private final CommerceService commerce;

    public TrackingController(OrganizationRepository orgs, SecretCipher cipher, JdbcTemplate jdbc, ContactRepository contacts, ContactEventService events,
                              CommerceService commerce) {
        this.orgs = orgs;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.events = events;
        this.commerce = commerce;
    }

    public record Identity(@NotBlank @Pattern(regexp = "email|phone|externalId") String kind, @NotBlank @Size(max = 320) String value, long ts,
                           @NotBlank String sig) {}

    public record Order(@NotBlank String orderId, long amountMinor, @NotBlank String currency, @NotBlank String sig) {}

    public record TrackRequest(@NotBlank String pk, @NotBlank @Size(max = 64) String anonymousId,
                               @NotBlank @Pattern(regexp = "identify|page_view|product_view|form_started|form_submitted|checkout_started|purchase_completed|checkout_abandoned") String event,
                               boolean consent, @Size(max = 64) String sessionId, @Size(max = 1000) String url, Identity identity, Order order) {}

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> track(@Valid @RequestBody TrackRequest r) {
        if (!r.consent()) return ResponseEntity.noContent().build();
        var org = orgs.findByPublicKey(r.pk()).orElse(null);
        if (org == null) return ResponseEntity.noContent().build();
        String secret = cipher.decrypt(org.getTrackingSecretEnc());
        Optional<UUID> contactId = Optional.empty();
        if (r.identity() != null) {
            Instant ts = Instant.ofEpochSecond(r.identity().ts());
            boolean fresh = ts.isAfter(Instant.now().minus(IDENTITY_MAX_AGE)) && ts.isBefore(Instant.now().plusSeconds(300));
            String expected = Tokens.hmacSha256Hex(secret, r.identity().kind() + ":" + r.identity().value() + "|" + r.identity().ts());
            if (!fresh || !Tokens.constantTimeEquals(expected, r.identity().sig())) return ResponseEntity.status(401).body(Map.of("error", "invalid identity signature"));
            var found = switch (r.identity().kind()) {
                case "email" -> contacts.findByEmail(org.getId(), r.identity().value().toLowerCase());
                case "phone" -> contacts.findByPhone(org.getId(), r.identity().value());
                default -> contacts.findByExternalId(org.getId(), r.identity().value());
            };
            contactId = found.map(c -> c.id());
            contactId.ifPresent(cid -> jdbc.update("""
                    INSERT INTO contact_identifiers (id, organization_id, contact_id, kind, value) VALUES (?,?,?, 'SDK_ANON_ID', ?)
                    ON CONFLICT (organization_id, kind, value) DO UPDATE SET contact_id = EXCLUDED.contact_id""", Ids.newId(), org.getId(), cid, r.anonymousId()));
        }
        if (contactId.isEmpty()) {
            contactId = jdbc.queryForList("SELECT contact_id FROM contact_identifiers WHERE organization_id = ? AND kind = 'SDK_ANON_ID' AND value = ?",
                    UUID.class, org.getId(), r.anonymousId()).stream().findFirst();
        }
        if (contactId.isEmpty() || "identify".equals(r.event())) return ResponseEntity.accepted().body(Map.of("identified", contactId.isPresent()));
        UUID cid = contactId.get();
        String session = r.sessionId() == null ? r.anonymousId() : r.sessionId();
        Instant now = Instant.now();
        switch (r.event()) {
            case "page_view" -> events.record(org.getId(), cid, "PAGE_VIEW", now, "SDK", null, Map.of("url", nz(r.url())));
            case "product_view" -> events.record(org.getId(), cid, "PRODUCT_VIEW", now, "SDK", null, Map.of("url", nz(r.url())));
            case "form_started" -> events.record(org.getId(), cid, "FORM_STARTED", now, "SDK", "form-start:" + session, Map.of("url", nz(r.url())));
            case "form_submitted" -> events.record(org.getId(), cid, "FORM_SUBMITTED", now, "SDK", "form:" + session, Map.of("url", nz(r.url())));
            // Debounced: one CHECKOUT_STARTED per session. Abandonment = automation WAIT + "no purchase since trigger".
            case "checkout_started" -> events.record(org.getId(), cid, "CHECKOUT_STARTED", now, "SDK", "checkout:" + session, Map.of("url", nz(r.url())));
            case "checkout_abandoned" -> {
                // Auxiliary signal (session expiry). Accepted only if a checkout started in this session and no purchase followed.
                boolean started = !jdbc.queryForList("SELECT 1 FROM contact_events WHERE organization_id = ? AND idempotency_key = ?", org.getId(), "checkout:" + session).isEmpty();
                boolean purchased = !jdbc.queryForList("SELECT 1 FROM contact_events WHERE contact_id = ? AND type = 'PURCHASE_COMPLETED' AND occurred_at > now() - interval '24 hours'", cid).isEmpty();
                if (started && !purchased) events.record(org.getId(), cid, "CHECKOUT_ABANDONED", now, "SDK", "abandon:" + session, Map.of());
            }
            case "purchase_completed" -> {
                Order o = r.order();
                boolean signed = o != null && Tokens.constantTimeEquals(Tokens.hmacSha256Hex(secret, o.orderId() + "|" + o.amountMinor() + "|" + o.currency()), o.sig());
                if (signed) {
                    commerce.upsertOrder(org.getId(), new CommerceService.OrderInput(o.orderId(), cid, "COMPLETED", o.amountMinor(), o.currency(), now), "SDK_SIGNED");
                } else {
                    // Unverified: stops abandonment flows (exit event) but is not counted as revenue.
                    events.record(org.getId(), cid, "PURCHASE_COMPLETED", now, "SDK_UNVERIFIED", "purchase:" + session, Map.of("verified", false));
                }
            }
            default -> { }
        }
        return ResponseEntity.accepted().body(Map.of("identified", true));
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
