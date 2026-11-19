package com.platform.administration;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.Ids;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support")
@Tag(name = "support")
public class SupportController {
    private final JdbcTemplate jdbc;

    public SupportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record TicketInput(@NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 10000) String body, @NotBlank String category) {}

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.ORG_READ)
    public Map<String, UUID> open(@Valid @RequestBody TicketInput in) {
        var t = TenantContext.current();
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO support_tickets (id, organization_id, opened_by, subject, body, category) VALUES (?,?,?,?,?,?)",
                id, t.orgId(), t.userId(), in.subject(), in.body(), in.category());
        return Map.of("id", id);
    }

    @GetMapping("/tickets")
    @RequiresPermission(Permission.ORG_READ)
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, subject, category, status, created_at FROM support_tickets WHERE organization_id = ? ORDER BY created_at DESC",
                TenantContext.current().orgId());
    }
}
