package com.platform.crm;

import com.platform.billing.EntitlementService;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Personas: customer profiles defined by the store ("Mãe prática", "Atacado"…), linked to the products they buy and to the
 * customers that fit them. Insights per persona come from the PDV/orders: customers, average ticket, the weekday they buy
 * most and the products they buy most. Plan feature PERSONAS (Alerta + Vendas and up).
 */
@RestController
@RequestMapping("/api/v1/crm/personas")
@Tag(name = "crm")
public class PersonasController {
    static final String[] WEEKDAYS = {"SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"};
    private final NamedParameterJdbcTemplate named;
    private final EntitlementService entitlements;

    public PersonasController(NamedParameterJdbcTemplate named, EntitlementService entitlements) {
        this.named = named;
        this.entitlements = entitlements;
    }

    public record PersonaInput(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
                               @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color, List<UUID> productIds) {}

    public record ProductRef(UUID id, String name) {}

    public record Persona(UUID id, String name, String description, String color, List<ProductRef> products, long customers,
                          Long averageTicketMinor, String currency, long purchases, String topWeekday, List<String> topProducts) {}

    private UUID org() {
        UUID org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.PERSONAS);
        return org;
    }

    @GetMapping
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<Persona> list() {
        UUID org = org();
        String zone = named.queryForObject("SELECT time_zone FROM organizations WHERE id = :org", new MapSqlParameterSource("org", org), String.class);
        var ids = named.queryForList("SELECT id FROM personas WHERE organization_id = :org ORDER BY name", new MapSqlParameterSource("org", org), UUID.class);
        List<Persona> out = new ArrayList<>();
        for (UUID id : ids) out.add(persona(org, id, zone));
        return out;
    }

    private Persona persona(UUID org, UUID id, String zone) {
        var p = new MapSqlParameterSource("org", org).addValue("id", id).addValue("zone", zone);
        var base = named.queryForMap("SELECT name, description, color FROM personas WHERE id = :id AND organization_id = :org", p);
        var products = named.query("""
                SELECT pr.id, pr.name FROM persona_products pp JOIN products pr ON pr.id = pp.product_id WHERE pp.persona_id = :id ORDER BY pr.name""",
                p, (rs, i) -> new ProductRef(rs.getObject(1, UUID.class), rs.getString(2)));
        var stats = named.queryForMap("""
                SELECT (SELECT count(*) FROM contact_personas WHERE persona_id = :id) AS customers,
                       count(o.id) AS purchases, CASE WHEN count(o.id) = 0 THEN NULL ELSE sum(o.amount_minor) / count(o.id) END AS ticket, max(o.currency) AS currency
                FROM contact_personas cp JOIN orders o ON o.contact_id = cp.contact_id AND o.organization_id = :org AND o.status = 'COMPLETED'
                WHERE cp.persona_id = :id""", p);
        Integer dow = named.query("""
                SELECT extract(dow FROM o.occurred_at AT TIME ZONE :zone)::int FROM contact_personas cp
                JOIN orders o ON o.contact_id = cp.contact_id AND o.organization_id = :org AND o.status = 'COMPLETED'
                WHERE cp.persona_id = :id GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1""", p, (rs, i) -> rs.getInt(1)).stream().findFirst().orElse(null);
        var top = named.queryForList("""
                SELECT i.description FROM contact_personas cp JOIN orders o ON o.contact_id = cp.contact_id AND o.organization_id = :org AND o.status = 'COMPLETED'
                JOIN order_items i ON i.order_id = o.id WHERE cp.persona_id = :id GROUP BY i.description ORDER BY sum(i.quantity) DESC LIMIT 3""", p, String.class);
        return new Persona(id, (String) base.get("name"), (String) base.get("description"), (String) base.get("color"), products,
                ((Number) stats.get("customers")).longValue(), stats.get("ticket") == null ? null : ((Number) stats.get("ticket")).longValue(),
                (String) stats.get("currency"), ((Number) stats.get("purchases")).longValue(), dow == null ? null : WEEKDAYS[dow], top);
    }

    @PostMapping
    @RequiresPermission(Permission.CONTACTS_WRITE)
    @Transactional
    public Persona create(@Valid @RequestBody PersonaInput in) {
        UUID org = org();
        UUID id = Ids.newId();
        try {
            named.update("INSERT INTO personas (id, organization_id, name, description, color) VALUES (:id, :org, :name, :desc, :color)",
                    new MapSqlParameterSource("id", id).addValue("org", org).addValue("name", in.name().trim()).addValue("desc", in.description()).addValue("color", in.color()));
        } catch (DuplicateKeyException e) { throw ApiException.conflict("A persona with this name already exists"); }
        setProducts(org, id, in.productIds());
        return persona(org, id, zone(org));
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    @Transactional
    public Persona update(@PathVariable UUID id, @Valid @RequestBody PersonaInput in) {
        UUID org = org();
        try {
            int n = named.update("UPDATE personas SET name = :name, description = :desc, color = :color, updated_at = now() WHERE id = :id AND organization_id = :org",
                    new MapSqlParameterSource("id", id).addValue("org", org).addValue("name", in.name().trim()).addValue("desc", in.description()).addValue("color", in.color()));
            if (n == 0) throw ApiException.notFound("Persona");
        } catch (DuplicateKeyException e) { throw ApiException.conflict("A persona with this name already exists"); }
        if (in.productIds() != null) setProducts(org, id, in.productIds());
        return persona(org, id, zone(org));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public void delete(@PathVariable UUID id) {
        UUID org = org();
        if (named.update("DELETE FROM personas WHERE id = :id AND organization_id = :org", new MapSqlParameterSource("id", id).addValue("org", org)) == 0) {
            throw ApiException.notFound("Persona");
        }
    }

    public record Assign(List<UUID> contactIds) {}

    /** Puts customers in a persona (a customer may fit more than one). */
    @PostMapping("/{id}/customers")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    @Transactional
    public Map<String, Integer> assign(@PathVariable UUID id, @RequestBody Assign body) {
        UUID org = org();
        mustOwn(org, id);
        int added = 0;
        for (UUID c : body.contactIds() == null ? List.<UUID>of() : body.contactIds()) {
            added += named.update("""
                    INSERT INTO contact_personas (contact_id, persona_id) SELECT c.id, :p FROM contacts c WHERE c.id = :c AND c.organization_id = :org
                    ON CONFLICT DO NOTHING""", new MapSqlParameterSource("p", id).addValue("c", c).addValue("org", org));
        }
        return Map.of("added", added);
    }

    @DeleteMapping("/{id}/customers/{contactId}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public void unassign(@PathVariable UUID id, @PathVariable UUID contactId) {
        UUID org = org();
        mustOwn(org, id);
        named.update("DELETE FROM contact_personas WHERE persona_id = :p AND contact_id = :c", new MapSqlParameterSource("p", id).addValue("c", contactId));
    }

    private void setProducts(UUID org, UUID persona, List<UUID> productIds) {
        if (productIds == null) return;
        var p = new MapSqlParameterSource("p", persona).addValue("org", org);
        named.update("DELETE FROM persona_products WHERE persona_id = :p", p);
        for (UUID prod : new LinkedHashSet<>(productIds)) {
            int n = named.update("INSERT INTO persona_products (persona_id, product_id) SELECT :p, id FROM products WHERE id = :prod AND organization_id = :org",
                    new MapSqlParameterSource("p", persona).addValue("prod", prod).addValue("org", org));
            if (n == 0) throw ApiException.notFound("Product");
        }
    }

    private void mustOwn(UUID org, UUID persona) {
        Long n = named.queryForObject("SELECT count(*) FROM personas WHERE id = :id AND organization_id = :org", new MapSqlParameterSource("id", persona).addValue("org", org), Long.class);
        if (n == null || n == 0) throw ApiException.notFound("Persona");
    }

    private String zone(UUID org) {
        return named.queryForObject("SELECT time_zone FROM organizations WHERE id = :org", new MapSqlParameterSource("org", org), String.class);
    }
}
