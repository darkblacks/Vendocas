package com.platform.sales;

import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Sales Registry endpoints (web and mobile "+ Venda"). Requires the SALES_REGISTRY plan feature. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "sales")
public class SalesController {
    private final SalesService sales;
    private final JdbcTemplate jdbc;

    public SalesController(SalesService sales, JdbcTemplate jdbc) {
        this.sales = sales;
        this.jdbc = jdbc;
    }

    @GetMapping("/products")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<SalesService.ProductView> products(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return sales.products(TenantContext.current().orgId(), q == null || q.isBlank() ? null : q, includeInactive);
    }

    @PostMapping("/products")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> createProduct(@Valid @RequestBody SalesService.ProductInput in) {
        var t = TenantContext.current();
        return Map.of("id", sales.saveProduct(t.orgId(), t.userId(), null, in));
    }

    @PutMapping("/products/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Map<String, UUID> updateProduct(@PathVariable UUID id, @Valid @RequestBody SalesService.ProductInput in) {
        var t = TenantContext.current();
        return Map.of("id", sales.saveProduct(t.orgId(), t.userId(), id, in));
    }

    @PostMapping("/sales")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public SalesService.SaleResult register(@Valid @RequestBody SalesService.SaleInput in) {
        var t = TenantContext.current();
        return sales.register(t.orgId(), t.userId(), in);
    }

    /** Sales of a day (default: today in the store's time zone) or of a [from, to) range given as dates. */
    @GetMapping("/sales")
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<SalesService.SaleView> list(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                            @RequestParam(required = false) String salesChannel) {
        UUID org = TenantContext.current().orgId();
        Instant[] range = range(org, from, to);
        return sales.list(org, range[0], range[1], salesChannel);
    }

    @GetMapping("/sales/summary")
    @RequiresPermission(Permission.REPORTS_READ)
    public SalesService.SalesSummary summary(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        UUID org = TenantContext.current().orgId();
        Instant[] range = range(org, from, to);
        return sales.summary(org, range[0], range[1]);
    }

    private Instant[] range(UUID org, LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.of(jdbc.queryForObject("SELECT time_zone FROM organizations WHERE id = ?", String.class, org));
        LocalDate f = from == null ? LocalDate.now(zone) : from;
        LocalDate t = to == null ? f : to;
        if (t.isBefore(f)) throw ApiException.badRequest("'to' must not be before 'from'");
        if (Duration.between(f.atStartOfDay(), t.atStartOfDay()).toDays() > 366) throw ApiException.badRequest("Range limited to one year");
        return new Instant[]{f.atStartOfDay(zone).toInstant(), t.plusDays(1).atStartOfDay(zone).toInstant()};
    }
}
