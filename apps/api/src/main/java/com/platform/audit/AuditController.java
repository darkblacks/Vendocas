package com.platform.audit;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "audit")
public class AuditController {
    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    @RequiresPermission(Permission.AUDIT_READ)
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT a.action, a.actor_type, u.email AS actor_email, a.target_type, a.target_id, a.created_at FROM audit_logs a
                LEFT JOIN users u ON u.id = a.actor_user_id WHERE a.organization_id = ? ORDER BY a.created_at DESC LIMIT 300""", TenantContext.current().orgId());
    }
}
