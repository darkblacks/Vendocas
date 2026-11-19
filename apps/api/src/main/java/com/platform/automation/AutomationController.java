package com.platform.automation;

import com.platform.automation.model.AutomationDefinition;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/automations")
@Tag(name = "automations")
public class AutomationController {
    private final AutomationService service;
    private final AutomationSimulator simulator;
    private final JdbcTemplate jdbc;

    public AutomationController(AutomationService service, AutomationSimulator simulator, JdbcTemplate jdbc) {
        this.service = service;
        this.simulator = simulator;
        this.jdbc = jdbc;
    }

    public record SaveRequest(@NotBlank @Size(max = 150) String name, @Size(max = 1000) String description, String editorMode,
                              @NotNull AutomationDefinition definition) {}

    @GetMapping
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public List<AutomationService.Summary> list() { return service.list(TenantContext.current().orgId()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    public Map<String, UUID> create(@Valid @RequestBody SaveRequest r) {
        var t = TenantContext.current();
        return Map.of("id", service.create(t.orgId(), t.userId(), r.name(), r.description(), r.editorMode(), r.definition()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public AutomationService.Detail get(@PathVariable UUID id) { return service.get(TenantContext.current().orgId(), id); }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    public AutomationService.Detail save(@PathVariable UUID id, @Valid @RequestBody SaveRequest r) {
        var t = TenantContext.current();
        service.saveDraft(t.orgId(), t.userId(), id, r.name(), r.description(), r.editorMode(), r.definition());
        return service.get(t.orgId(), id);
    }

    public record ActivateRequest(String migrationPolicy) {}

    @PostMapping("/{id}/activate")
    @RequiresPermission(Permission.AUTOMATIONS_ACTIVATE)
    public AutomationService.Detail activate(@PathVariable UUID id, @RequestBody(required = false) ActivateRequest r) {
        var t = TenantContext.current();
        service.activate(t.orgId(), t.userId(), id, r == null ? null : r.migrationPolicy());
        return service.get(t.orgId(), id);
    }

    @PostMapping("/{id}/pause")
    @RequiresPermission(Permission.AUTOMATIONS_ACTIVATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void pause(@PathVariable UUID id) {
        var t = TenantContext.current();
        service.setStatus(t.orgId(), t.userId(), id, "PAUSED");
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.AUTOMATIONS_ACTIVATE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable UUID id) {
        var t = TenantContext.current();
        service.setStatus(t.orgId(), t.userId(), id, "ARCHIVED");
    }

    @PostMapping("/{id}/duplicate")
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    public Map<String, UUID> duplicate(@PathVariable UUID id) {
        var t = TenantContext.current();
        return Map.of("id", service.duplicate(t.orgId(), t.userId(), id));
    }

    @GetMapping("/{id}/versions")
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public List<AutomationService.VersionView> versions(@PathVariable UUID id) { return service.versions(TenantContext.current().orgId(), id); }

    public record SimulateRequest(@NotNull AutomationDefinition definition, UUID contactId, AutomationSimulator.SampleContact sample, Instant start) {}

    @PostMapping("/simulate")
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public List<AutomationSimulator.SimulationStep> simulate(@Valid @RequestBody SimulateRequest r) {
        return simulator.simulate(TenantContext.current().orgId(), r.definition(), r.contactId(), r.sample(), r.start());
    }

    @PostMapping("/estimate")
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public AutomationSimulator.Estimate estimate(@Valid @RequestBody SimulateRequest r) {
        return simulator.estimate(TenantContext.current().orgId(), r.definition());
    }

    public record TestRunRequest(@NotNull UUID contactId) {}

    @PostMapping("/{id}/test-run")
    @RequiresPermission(Permission.AUTOMATIONS_WRITE)
    public List<AutomationSimulator.TestResult> testRun(@PathVariable UUID id, @Valid @RequestBody TestRunRequest r) {
        UUID org = TenantContext.current().orgId();
        var d = service.get(org, id);
        return simulator.testRun(org, d.draft() != null ? d.draft() : d.active(), id, r.contactId());
    }

    public record InstanceView(UUID id, UUID contactId, String contactName, String state, String currentStepId, Instant nextRunAt, String result,
                               String blockedReason, int versionNo, Instant startedAt, Instant completedAt) {}

    @GetMapping("/{id}/instances")
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public List<InstanceView> instances(@PathVariable UUID id, @RequestParam(required = false) String state) {
        return jdbc.query("""
                SELECT i.id, i.contact_id, c.name, i.state, i.current_step_id, i.next_run_at, i.result, i.blocked_reason, v.version_no, i.started_at, i.completed_at
                FROM automation_instances i JOIN automation_versions v ON v.id = i.version_id LEFT JOIN contacts c ON c.id = i.contact_id
                WHERE i.organization_id = ? AND i.automation_id = ? AND (?::text IS NULL OR i.state = ?) ORDER BY i.started_at DESC LIMIT 200""",
                (rs, n) -> new InstanceView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8), rs.getInt(9),
                        rs.getTimestamp(10).toInstant(), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()),
                TenantContext.current().orgId(), id, state, state);
    }

    public record ExecutionView(String stepId, String stepType, String outcome, String detail, UUID messageId, Instant createdAt) {}

    @GetMapping("/instances/{instanceId}/executions")
    @RequiresPermission(Permission.AUTOMATIONS_READ)
    public List<ExecutionView> executions(@PathVariable UUID instanceId) {
        return jdbc.query("""
                SELECT step_id, step_type, outcome, detail, message_id, created_at FROM automation_executions
                WHERE organization_id = ? AND instance_id = ? ORDER BY created_at""",
                (rs, n) -> new ExecutionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class),
                        rs.getTimestamp(6).toInstant()), TenantContext.current().orgId(), instanceId);
    }
}
