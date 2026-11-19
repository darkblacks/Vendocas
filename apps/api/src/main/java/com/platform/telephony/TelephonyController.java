package com.platform.telephony;

import com.platform.billing.EntitlementService;
import com.platform.contacts.Phones;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/calls")
@Tag(name = "calls")
public class TelephonyController {
    private final JdbcTemplate jdbc;
    private final EntitlementService entitlements;

    public TelephonyController(JdbcTemplate jdbc, EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
    }

    public record NumberView(UUID id, String e164, String countryCode, String ownership, String capabilities, String status, String forwardTo,
                             Instant testCallPassedAt) {}

    @GetMapping("/numbers")
    @RequiresPermission(Permission.CALLS_READ)
    public List<NumberView> numbers() {
        return jdbc.query("""
                SELECT id, e164, country_code, ownership, capabilities, status, forward_to_e164, test_call_passed_at FROM phone_numbers
                WHERE organization_id = ? AND status <> 'RELEASED' ORDER BY created_at""",
                (rs, i) -> new NumberView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant()), TenantContext.current().orgId());
    }

    public record RoutingRequest(@NotBlank @Pattern(regexp = "FORWARD_TO_BUSINESS|CONDITIONAL_FORWARD_IN") String mode, String businessPhone) {}

    /**
     * Configures how the provider number is used for missed-call detection. Monitoring starts only
     * after a real test call is observed (status TEST_PENDING → MONITORING).
     */
    @PutMapping("/numbers/{id}/routing")
    @RequiresPermission(Permission.CALLS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void routing(@PathVariable UUID id, @Valid @RequestBody RoutingRequest r) {
        UUID org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.CHANNEL_VOICE);
        String forward = null;
        if ("FORWARD_TO_BUSINESS".equals(r.mode())) {
            String country = jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, org);
            forward = Phones.toE164(r.businessPhone(), country).orElseThrow(() -> ApiException.badRequest("Invalid business phone"));
        }
        int n = jdbc.update("""
                UPDATE phone_numbers SET ownership = ?, forward_to_e164 = ?, status = 'TEST_PENDING', test_call_passed_at = NULL, updated_at = now()
                WHERE id = ? AND organization_id = ? AND provider = 'TWILIO'""",
                "CONDITIONAL_FORWARD_IN".equals(r.mode()) ? "FORWARDED" : "PURCHASED", forward, id, org);
        if (n == 0) throw ApiException.notFound("Number");
    }

    public record CallView(UUID id, String direction, String from, String to, String status, Boolean answered, Integer durationSeconds,
                           UUID contactId, String contactName, boolean test, Instant createdAt) {}

    @GetMapping
    @RequiresPermission(Permission.CALLS_READ)
    public List<CallView> calls(@RequestParam(defaultValue = "false") boolean missedOnly) {
        return jdbc.query("""
                SELECT e.id, e.direction, e.from_e164, e.to_e164, e.status, e.answered, e.duration_seconds, e.contact_id, c.name, e.is_test, e.created_at
                FROM call_events e LEFT JOIN contacts c ON c.id = e.contact_id
                WHERE e.organization_id = ? AND (? = false OR EXISTS (SELECT 1 FROM missed_calls m WHERE m.call_event_id = e.id))
                ORDER BY e.created_at DESC LIMIT 200""",
                (rs, i) -> new CallView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        (Boolean) rs.getObject(6), (Integer) rs.getObject(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getBoolean(10),
                        rs.getTimestamp(11).toInstant()), TenantContext.current().orgId(), missedOnly);
    }

    public record TaskView(UUID id, UUID contactId, String contactName, String phone, String sourceType, String status, UUID assignedTo, Instant dueAt,
                           String outcome, String notes, Instant createdAt) {}

    @GetMapping("/tasks")
    @RequiresPermission(Permission.CALLS_READ)
    public List<TaskView> tasks(@RequestParam(defaultValue = "OPEN") String status) {
        return jdbc.query("""
                SELECT t.id, t.contact_id, c.name, t.phone_e164, t.source_type, t.status, t.assigned_to, t.due_at, t.outcome, t.notes, t.created_at
                FROM callback_tasks t LEFT JOIN contacts c ON c.id = t.contact_id
                WHERE t.organization_id = ? AND t.status = ? ORDER BY t.due_at NULLS LAST LIMIT 200""",
                (rs, i) -> new TaskView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, UUID.class), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getString(9),
                        rs.getString(10), rs.getTimestamp(11).toInstant()), TenantContext.current().orgId(), status);
    }

    public record TaskUpdate(@Pattern(regexp = "OPEN|IN_PROGRESS|DONE|CANCELED") String status,
                             @Pattern(regexp = "REACHED|NO_ANSWER|WRONG_NUMBER|SCHEDULED|SOLD") String outcome, String notes, UUID assignedTo) {}

    @PatchMapping("/tasks/{id}")
    @RequiresPermission(Permission.CALLS_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateTask(@PathVariable UUID id, @Valid @RequestBody TaskUpdate u) {
        UUID org = TenantContext.current().orgId();
        int n = jdbc.update("""
                UPDATE callback_tasks SET status = COALESCE(?, status), outcome = COALESCE(?, outcome), notes = COALESCE(?, notes),
                  assigned_to = COALESCE(?, assigned_to), completed_at = CASE WHEN ? IN ('DONE','CANCELED') THEN now() ELSE completed_at END, updated_at = now()
                WHERE id = ? AND organization_id = ?""", u.status(), u.outcome(), u.notes(), u.assignedTo(), u.status(), id, org);
        if (n == 0) throw ApiException.notFound("Task");
        if ("DONE".equals(u.status())) {
            jdbc.update("""
                    UPDATE opportunities SET status = CASE WHEN ? = 'SOLD' THEN 'WON' ELSE 'CONTACTED' END, updated_at = now()
                    WHERE organization_id = ? AND source_type = 'MISSED_CALL' AND source_id IN (SELECT id FROM missed_calls WHERE callback_task_id = ?)""",
                    u.outcome(), org, id);
        }
    }
}
