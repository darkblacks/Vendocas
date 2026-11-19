package com.platform.tenants;

import com.platform.security.CurrentUser;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.TenantDtos.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "organizations")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) { this.service = service; }

    @PostMapping("/organizations")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationView create(@Valid @RequestBody CreateOrganizationRequest req) {
        return OrganizationView.of(service.create(CurrentUser.id(), req));
    }

    @GetMapping("/organizations/current")
    @RequiresPermission(Permission.ORG_READ)
    public OrganizationView current() { return OrganizationView.of(service.get(TenantContext.current().orgId())); }

    @PutMapping("/organizations/current")
    @RequiresPermission(Permission.ORG_MANAGE)
    public OrganizationView update(@Valid @RequestBody UpdateOrganizationRequest req) {
        var t = TenantContext.current();
        return OrganizationView.of(service.update(t.orgId(), t.userId(), req));
    }

    @GetMapping("/organizations/current/tax-profile")
    @RequiresPermission(Permission.ORG_READ)
    public TaxProfileView taxProfile() { return service.taxProfile(TenantContext.current().orgId()); }

    @PutMapping("/organizations/current/tax-profile")
    @RequiresPermission(Permission.ORG_MANAGE)
    public TaxProfileView updateTax(@Valid @RequestBody TaxIdInput input) {
        var t = TenantContext.current();
        return service.updateTaxId(t.orgId(), t.userId(), input);
    }

    @PostMapping("/organizations/current/tax-profile/verify")
    @RequiresPermission(Permission.ORG_MANAGE)
    public TaxProfileView verifyTax() {
        var t = TenantContext.current();
        return service.verifyTaxId(t.orgId(), t.userId());
    }

    @GetMapping("/organizations/current/settings")
    @RequiresPermission(Permission.ORG_READ)
    public SettingsView settings() { return service.settings(TenantContext.current().orgId()); }

    @PutMapping("/organizations/current/settings")
    @RequiresPermission(Permission.ORG_MANAGE)
    public SettingsView updateSettings(@Valid @RequestBody UpdateSettingsRequest req) {
        var t = TenantContext.current();
        return service.updateSettings(t.orgId(), t.userId(), req);
    }

    @PostMapping("/organizations/current/tracking-secret/rotate")
    @RequiresPermission(Permission.INTEGRATIONS_MANAGE)
    public TrackingKeysView rotateTracking() {
        var t = TenantContext.current();
        return service.rotateTrackingSecret(t.orgId(), t.userId());
    }

    @GetMapping("/members")
    @RequiresPermission(Permission.ORG_READ)
    public List<MemberView> members() { return service.members(TenantContext.current().orgId()); }

    @PostMapping("/members/invitations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    public void invite(@Valid @RequestBody InviteRequest req) {
        var t = TenantContext.current();
        service.invite(t.orgId(), t.userId(), req);
    }

    @PostMapping("/members/invitations/accept")
    public Map<String, UUID> accept(@RequestBody Map<String, String> body) {
        return Map.of("organizationId", service.acceptInvitation(CurrentUser.id(), body.getOrDefault("token", "")));
    }

    @PutMapping("/members/{userId}/role")
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(@PathVariable UUID userId, @Valid @RequestBody ChangeRoleRequest req) {
        var t = TenantContext.current();
        service.changeRole(t.orgId(), t.userId(), t.role(), userId, Role.valueOf(req.role()));
    }

    @DeleteMapping("/members/{userId}")
    @RequiresPermission(Permission.MEMBERS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID userId) {
        var t = TenantContext.current();
        service.removeMember(t.orgId(), t.userId(), t.role(), userId);
    }
}
