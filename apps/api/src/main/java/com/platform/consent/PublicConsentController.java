package com.platform.consent;

import com.platform.contacts.ContactService;
import com.platform.contacts.ContactDtos.ContactInput;
import com.platform.contacts.ContactEventService;
import com.platform.shared.api.ApiException;
import com.platform.shared.web.ClientIp;
import com.platform.tenants.OrganizationRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public endpoints used by consumers: one-click unsubscribe and consent-capturing sign-up forms. */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "public")
public class PublicConsentController {
    private final UnsubscribeTokens tokens;
    private final ConsentService consents;
    private final JdbcTemplate jdbc;
    private final OrganizationRepository orgs;
    private final ContactService contacts;
    private final ContactEventService events;

    public PublicConsentController(UnsubscribeTokens tokens, ConsentService consents, JdbcTemplate jdbc, OrganizationRepository orgs,
                                   ContactService contacts, ContactEventService events) {
        this.tokens = tokens;
        this.consents = consents;
        this.jdbc = jdbc;
        this.orgs = orgs;
        this.contacts = contacts;
        this.events = events;
    }

    /** Landing page for the link (GET shows a confirmation; POST performs it — avoids link scanners unsubscribing people). */
    @GetMapping(value = "/unsubscribe/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribePage(@PathVariable String token) {
        tokens.verify(token).orElseThrow(() -> ApiException.badRequest("Invalid link"));
        String html = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Unsubscribe</title></head>
                <body style="font-family:system-ui;max-width:480px;margin:64px auto;padding:0 16px">
                <h1>Unsubscribe</h1><p>Stop receiving promotional messages from this business on this channel?</p>
                <form method="post"><button style="padding:10px 18px">Unsubscribe</button></form></body></html>""";
        return ResponseEntity.ok().header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'").body(html);
    }

    /** RFC 8058 one-click (List-Unsubscribe-Post) and the confirmation form both POST here. */
    @PostMapping(value = "/unsubscribe/{token}", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional
    public ResponseEntity<String> unsubscribe(@PathVariable String token) {
        var t = tokens.verify(token).orElseThrow(() -> ApiException.badRequest("Invalid link"));
        String address = jdbc.queryForObject("SELECT CASE WHEN ? = 'EMAIL' THEN email::text ELSE phone_e164 END FROM contacts WHERE id = ? AND organization_id = ?",
                String.class, t.channel(), t.contactId(), t.orgId());
        consents.optOutMarketing(t.orgId(), t.contactId(), t.channel(), address, "UNSUBSCRIBE_LINK");
        return ResponseEntity.ok("<!doctype html><html><body style=\"font-family:system-ui;max-width:480px;margin:64px auto\"><h1>Done</h1><p>You will no longer receive promotional messages on this channel.</p></body></html>");
    }

    public record FormSubmission(@NotBlank String publicKey, @Size(max = 250) String name, @Email String email, @Size(max = 30) String phone,
                                 @NotNull List<@Pattern(regexp = "EMAIL|WHATSAPP|SMS") String> channels,
                                 @NotBlank @Pattern(regexp = "MARKETING|SERVICE_REMINDER|CUSTOMER_CARE") String purpose,
                                 @NotBlank @Size(max = 2000) String consentText, @Size(max = 1000) String pageUrl,
                                 @AssertTrue boolean consentChecked) {}

    /** Embedded sign-up form: creates/updates the contact and records consent with evidence (IP, UA, wording, page). */
    @PostMapping("/forms/submit")
    @Transactional
    public Map<String, String> submit(@Valid @RequestBody FormSubmission f, HttpServletRequest req) {
        var org = orgs.findByPublicKey(f.publicKey()).orElseThrow(() -> ApiException.badRequest("Unknown form key"));
        var entry = contacts.upsert(org.getId(), new ContactInput(null, f.name(), f.email(), f.phone(), null, null, null, null, null, null,
                null, null, null, null, List.of("form"), null), "FORM", f.pageUrl());
        var ev = new ConsentService.Evidence("FORM", "consumer", ClientIp.of(req), req.getHeader("User-Agent"),
                HtmlUtils.htmlEscape(f.consentText()), f.pageUrl(), Instant.now());
        for (String ch : f.channels()) consents.set(org.getId(), entry.getKey().id(), ch, f.purpose(), "GRANTED", "CONSENT", ev);
        events.record(org.getId(), entry.getKey().id(), "FORM_SUBMITTED", Instant.now(), "FORM", null, Map.of("page", f.pageUrl() == null ? "" : f.pageUrl()));
        return Map.of("status", "ok");
    }
}
