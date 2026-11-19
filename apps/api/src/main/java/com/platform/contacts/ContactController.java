package com.platform.contacts;

import com.platform.consent.ConsentService;
import com.platform.contacts.ContactDtos.*;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.PageResponse;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contacts")
@Tag(name = "contacts")
public class ContactController {
    private final ContactService service;
    private final ContactRepository repo;
    private final ConsentService consents;
    private final ImportService imports;

    public ContactController(ContactService service, ContactRepository repo, ConsentService consents, ImportService imports) {
        this.service = service;
        this.repo = repo;
        this.consents = consents;
        this.imports = imports;
    }

    @GetMapping
    @RequiresPermission(Permission.CONTACTS_READ)
    public PageResponse<Contact> list(@RequestParam(required = false) String q, @RequestParam(required = false) String tag,
                                      @RequestParam(required = false) String status, @RequestParam(required = false) String country,
                                      @RequestParam(required = false) Instant lastPurchaseBefore,
                                      @RequestParam(required = false) Instant lastInteractionBefore,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        UUID org = TenantContext.current().orgId();
        var f = new ContactRepository.Filter(q, tag, status, country, lastPurchaseBefore, lastInteractionBefore, null, null);
        int s = PageResponse.clampSize(size);
        long total = repo.count(org, f);
        return new PageResponse<>(repo.search(org, f, Math.max(0, page), s), page, s, total, (int) Math.ceil(total / (double) s));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Contact create(@Valid @RequestBody ContactInput in) {
        var t = TenantContext.current();
        return service.create(t.orgId(), in, "MANUAL", t.userId());
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.CONTACTS_READ)
    public Contact get(@PathVariable UUID id) { return service.get(TenantContext.current().orgId(), id); }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Contact update(@PathVariable UUID id, @Valid @RequestBody ContactInput in) {
        var t = TenantContext.current();
        return service.update(t.orgId(), id, in, t.userId());
    }

    /** Erasure (right to be forgotten). Irreversible. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.PRIVACY_MANAGE)
    public void erase(@PathVariable UUID id) {
        var t = TenantContext.current();
        service.erase(t.orgId(), id, t.userId());
    }

    @GetMapping("/{id}/timeline")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<TimelineItem> timeline(@PathVariable UUID id) { return service.timeline(TenantContext.current().orgId(), id); }

    @GetMapping("/{id}/consents")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<ConsentService.ConsentView> consents(@PathVariable UUID id) { return consents.list(TenantContext.current().orgId(), id); }

    public record ConsentChange(@NotBlank String channel, @NotBlank String purpose, @NotBlank String status, String legalBasis,
                                @NotBlank String source, String evidence) {}

    @PutMapping("/{id}/consents")
    @RequiresPermission(Permission.CONSENT_MANAGE)
    public List<ConsentService.ConsentView> setConsent(@PathVariable UUID id, @Valid @RequestBody ConsentChange c) {
        var t = TenantContext.current();
        if (!List.of("MANUAL", "IMPORT_DECLARED", "DOUBLE_OPT_IN").contains(c.source())) throw com.platform.shared.api.ApiException.badRequest("Invalid source");
        if ("GRANTED".equals(c.status()) && (c.evidence() == null || c.evidence().isBlank())) {
            throw com.platform.shared.api.ApiException.badRequest("Describe how this consent was obtained");
        }
        consents.set(t.orgId(), id, c.channel(), c.purpose(), c.status(), c.legalBasis(),
                new ConsentService.Evidence(c.source(), t.actor(), null, null, c.evidence(), null, Instant.now()));
        return consents.list(t.orgId(), id);
    }

    @GetMapping("/custom-fields")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<CustomFieldView> customFields() { return service.customFields(TenantContext.current().orgId()); }

    @PostMapping("/custom-fields")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public CustomFieldView addCustomField(@Valid @RequestBody CustomFieldInput in) { return service.upsertCustomField(TenantContext.current().orgId(), in); }

    // ---- imports ----
    @PostMapping(value = "/imports", consumes = "multipart/form-data")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public ImportService.UploadResult upload(@RequestParam("file") MultipartFile file) throws IOException {
        var t = TenantContext.current();
        return imports.upload(t.orgId(), t.userId(), file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/imports")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<ImportService.ImportView> imports() { return imports.list(TenantContext.current().orgId()); }

    @PutMapping("/imports/{id}/mapping")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public ImportService.Preview map(@PathVariable UUID id, @RequestBody ImportService.MappingRequest req) {
        return imports.map(TenantContext.current().orgId(), id, req);
    }

    @PostMapping("/imports/{id}/commit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public void commit(@PathVariable UUID id) {
        var t = TenantContext.current();
        imports.commit(t.orgId(), t.userId(), id);
    }

    @PostMapping("/imports/{id}/rollback")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, Integer> rollback(@PathVariable UUID id) {
        var t = TenantContext.current();
        return Map.of("deleted", imports.rollback(t.orgId(), t.userId(), id));
    }

    @GetMapping(value = "/imports/{id}/errors", produces = "application/json")
    @RequiresPermission(Permission.CONTACTS_READ)
    public String importErrors(@PathVariable UUID id) { return imports.errors(TenantContext.current().orgId(), id); }
}
