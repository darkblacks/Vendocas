package com.platform.notifications;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    @RequiresPermission(Permission.ORG_READ)
    public List<NotificationService.NotificationView> list(@RequestParam(defaultValue = "false") boolean unread) {
        return service.list(TenantContext.current().orgId(), unread);
    }

    @PostMapping("/{id}/read")
    @RequiresPermission(Permission.ORG_READ)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) { service.markRead(TenantContext.current().orgId(), id); }
}
