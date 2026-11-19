package com.platform.integrations;

import com.platform.commerce.CommerceService;
import com.platform.contacts.ContactDtos.ContactInput;
import com.platform.contacts.ContactEventService;
import com.platform.contacts.ContactService;
import com.platform.contacts.Phones;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.telephony.CallService;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Management of API keys (dashboard, JWT) and the server-to-server inbound API (API key):
 * contacts upsert, authenticated custom events, orders, appointments and PBX call events.
 */
@RestController
@RequestMapping("/api/v1/integrations")
@Tag(name = "integrations")
public class IntegrationController {
    private final ApiKeyService keys;
    private final ContactService contacts;
    private final ContactEventService events;
    private final CommerceService commerce;
    private final CallService calls;

    public IntegrationController(ApiKeyService keys, ContactService contacts, ContactEventService events, CommerceService commerce, CallService calls) {
        this.keys = keys;
        this.contacts = contacts;
        this.events = events;
        this.commerce = commerce;
        this.calls = calls;
    }

    public record KeyRequest(@NotBlank String name, @NotNull List<String> scopes) {}

    @PostMapping("/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.INTEGRATIONS_MANAGE)
    public ApiKeyService.Created createKey(@Valid @RequestBody KeyRequest r) {
        var t = TenantContext.current();
        return keys.create(t.orgId(), t.userId(), r.name(), r.scopes());
    }

    @GetMapping("/api-keys")
    @RequiresPermission(Permission.INTEGRATIONS_MANAGE)
    public List<ApiKeyService.KeyView> listKeys() { return keys.list(TenantContext.current().orgId()); }

    @DeleteMapping("/api-keys/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.INTEGRATIONS_MANAGE)
    public void revoke(@PathVariable UUID id) {
        var t = TenantContext.current();
        keys.revoke(t.orgId(), t.userId(), id);
    }

    // ---------- server-to-server (API key) ----------
    @PostMapping("/inbound/contacts")
    public Map<String, Object> upsertContact(@RequestHeader(name = "Authorization", required = false) String auth, @Valid @RequestBody ContactInput in) {
        var p = keys.authenticate(auth, "contacts:write");
        var r = contacts.upsert(p.orgId(), in, "API", "key:" + p.keyId());
        return Map.of("contactId", r.getKey().id(), "created", r.getValue());
    }

    public record EventRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9_.:-]{1,60}$") String type, UUID contactId, String externalContactId,
                               String email, String phone, @NotBlank String idempotencyKey, Instant occurredAt, Map<String, Object> properties) {}

    /** Authenticated external event (e.g. QUOTE_CREATED, SERVICE_COMPLETED or any custom name for EXTERNAL_EVENT triggers). */
    @PostMapping("/inbound/events")
    @Transactional
    public Map<String, Object> event(@RequestHeader(name = "Authorization", required = false) String auth, @Valid @RequestBody EventRequest e) {
        var p = keys.authenticate(auth, "events:write");
        if (e.type().equals("PURCHASE_COMPLETED")) throw ApiException.badRequest("Use /inbound/orders for purchases");
        UUID contactId = e.contactId() != null ? contacts.get(p.orgId(), e.contactId()).id()
                : contacts.upsert(p.orgId(), new ContactInput(e.externalContactId(), null, e.email(), e.phone(), null, null, null, null, null, null, null, null,
                        null, null, null, null), "API", "key:" + p.keyId()).getKey().id();
        var id = events.record(p.orgId(), contactId, e.type(), e.occurredAt() == null ? Instant.now() : e.occurredAt(), "API", e.idempotencyKey(),
                e.properties() == null ? Map.of() : e.properties());
        return Map.of("contactId", contactId, "duplicate", id.isEmpty());
    }

    public record OrderRequest(@NotBlank String externalId, UUID contactId, String externalContactId, String email, String phone,
                               @NotBlank String status, long amountMinor, @NotBlank String currency, Instant occurredAt) {}

    @PostMapping("/inbound/orders")
    @Transactional
    public Map<String, UUID> order(@RequestHeader(name = "Authorization", required = false) String auth, @Valid @RequestBody OrderRequest o) {
        var p = keys.authenticate(auth, "orders:write");
        UUID contactId = o.contactId();
        if (contactId == null && (o.email() != null || o.phone() != null || o.externalContactId() != null)) {
            contactId = contacts.upsert(p.orgId(), new ContactInput(o.externalContactId(), null, o.email(), o.phone(), null, null, null, null, null, null, null,
                    null, null, null, null, null), "API", "key:" + p.keyId()).getKey().id();
        }
        return Map.of("orderId", commerce.upsertOrder(p.orgId(), new CommerceService.OrderInput(o.externalId(), contactId, o.status(), o.amountMinor(),
                o.currency(), o.occurredAt()), "INTEGRATION"));
    }

    @PostMapping("/inbound/appointments")
    public Map<String, UUID> appointment(@RequestHeader(name = "Authorization", required = false) String auth, @RequestBody CommerceService.AppointmentInput a) {
        var p = keys.authenticate(auth, "appointments:write");
        return Map.of("appointmentId", commerce.upsertAppointment(p.orgId(), a, "INTEGRATION"));
    }

    public record PbxCall(@NotBlank String callId, @NotBlank @Pattern(regexp = "INBOUND|OUTBOUND") String direction, @NotBlank String from, @NotBlank String to,
                          @NotBlank @Pattern(regexp = "RINGING|IN_PROGRESS|COMPLETED|BUSY|NO_ANSWER|FAILED|CANCELED|MISSED") String status,
                          Integer durationSeconds, Instant startedAt, Instant endedAt) {}

    /** VoIP/PBX integration: the customer's PBX reports call outcomes with an API key. */
    @PostMapping("/inbound/calls")
    public Map<String, UUID> pbx(@RequestHeader(name = "Authorization", required = false) String auth, @Valid @RequestBody PbxCall c) {
        var p = keys.authenticate(auth, "calls:write");
        String country = null;
        String from = Phones.toE164(c.from(), country).orElse(c.from());
        String to = Phones.toE164(c.to(), country).orElse(c.to());
        return Map.of("callEventId", calls.record(new CallService.CallEvent(p.orgId(), null, "PBX:" + p.keyId(), c.callId(), c.direction(), from, to,
                c.status(), c.durationSeconds(), c.startedAt(), c.endedAt())));
    }
}
