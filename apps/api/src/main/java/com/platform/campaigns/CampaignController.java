package com.platform.campaigns;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/campaigns")
@Tag(name = "campaigns")
public class CampaignController {
    private final CampaignService service;

    public CampaignController(CampaignService service) { this.service = service; }

    @GetMapping
    @RequiresPermission(Permission.CAMPAIGNS_READ)
    public List<CampaignService.CampaignView> list() { return service.list(TenantContext.current().orgId()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.CAMPAIGNS_WRITE)
    public Map<String, UUID> create(@Valid @RequestBody CampaignService.CampaignInput in) {
        var t = TenantContext.current();
        return Map.of("id", service.create(t.orgId(), t.userId(), in));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CAMPAIGNS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID id, @Valid @RequestBody CampaignService.CampaignInput in) {
        var t = TenantContext.current();
        service.update(t.orgId(), t.userId(), id, in);
    }

    @PostMapping("/{id}/activate")
    @RequiresPermission(Permission.CAMPAIGNS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@PathVariable UUID id) {
        var t = TenantContext.current();
        service.activate(t.orgId(), t.userId(), id);
    }

    @PostMapping("/{id}/pause")
    @RequiresPermission(Permission.CAMPAIGNS_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@PathVariable UUID id) {
        var t = TenantContext.current();
        service.pause(t.orgId(), t.userId(), id, "Paused by user");
    }

    @GetMapping("/{id}/estimate")
    @RequiresPermission(Permission.CAMPAIGNS_READ)
    public CampaignService.Estimate estimate(@PathVariable UUID id) { return service.estimate(TenantContext.current().orgId(), id); }

    @GetMapping("/{id}/runs")
    @RequiresPermission(Permission.CAMPAIGNS_READ)
    public List<CampaignService.RunView> runs(@PathVariable UUID id) { return service.runs(TenantContext.current().orgId(), id); }
}
