package com.platform.commerce;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "commerce")
public class CommerceController {
    private final CommerceService service;
    private final JdbcTemplate jdbc;

    public CommerceController(CommerceService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    /** Manual registration of a sale (e.g. in-store). Source MANUAL. */
    @PostMapping("/orders")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> order(@RequestBody CommerceService.OrderInput in) {
        return Map.of("id", service.upsertOrder(TenantContext.current().orgId(), in, "MANUAL"));
    }

    @PostMapping("/appointments")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> appointment(@RequestBody CommerceService.AppointmentInput in) {
        return Map.of("id", service.upsertAppointment(TenantContext.current().orgId(), in, "MANUAL"));
    }

    public record AppointmentView(UUID id, UUID contactId, String contactName, Instant startsAt, String status, String title) {}

    @GetMapping("/appointments")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<AppointmentView> appointments() {
        return jdbc.query("""
                SELECT a.id, a.contact_id, c.name, a.starts_at, a.status, a.title FROM appointments a JOIN contacts c ON c.id = a.contact_id
                WHERE a.organization_id = ? AND a.starts_at > now() - interval '7 days' ORDER BY a.starts_at LIMIT 200""",
                (rs, i) -> new AppointmentView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getTimestamp(4).toInstant(),
                        rs.getString(5), rs.getString(6)), TenantContext.current().orgId());
    }

    public record OpportunityView(UUID id, UUID contactId, String contactName, String title, String status, String sourceType, Long valueMinor, Instant createdAt) {}

    @GetMapping("/opportunities")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<OpportunityView> opportunities(@RequestParam(defaultValue = "OPEN") String status) {
        return jdbc.query("""
                SELECT o.id, o.contact_id, c.name, o.title, o.status, o.source_type, o.value_minor, o.created_at FROM opportunities o
                LEFT JOIN contacts c ON c.id = o.contact_id WHERE o.organization_id = ? AND o.status = ? ORDER BY o.created_at DESC LIMIT 200""",
                (rs, i) -> new OpportunityView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), (Long) rs.getObject(7), rs.getTimestamp(8).toInstant()), TenantContext.current().orgId(), status);
    }
}
