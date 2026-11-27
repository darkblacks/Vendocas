package com.platform.crm;

import com.platform.billing.EntitlementService;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * B2B board: deals moved by hand through lead → proposal → negotiation → won / lost (plan feature PIPELINE).
 * Cards keep their order inside a column ({@code position}); moving a card sets its column and position.
 */
@RestController
@RequestMapping("/api/v1/crm/deals")
@Tag(name = "crm")
public class DealsController {
    public static final List<String> STAGES = List.of("LEAD", "PROPOSAL", "NEGOTIATION", "WON", "LOST");
    private final NamedParameterJdbcTemplate named;
    private final EntitlementService entitlements;

    public DealsController(NamedParameterJdbcTemplate named, EntitlementService entitlements) {
        this.named = named;
        this.entitlements = entitlements;
    }

    public record Deal(UUID id, UUID contactId, String contactName, String companyName, String title, String stage, double position,
                       Long valueMinor, String currency, LocalDate expectedCloseOn, String notes, String lostReason, Instant closedAt, Instant createdAt, Instant updatedAt) {}

    public record Column(String stage, long count, long valueMinor, List<Deal> deals) {}

    public record DealInput(UUID contactId, @Size(max = 200) String companyName, @NotBlank @Size(max = 200) String title,
                            @PositiveOrZero Long valueMinor, @Pattern(regexp = "[A-Z]{3}") String currency, LocalDate expectedCloseOn,
                            @Size(max = 2000) String notes) {}

    public record Move(@NotBlank String stage, Integer index, @Size(max = 300) String lostReason) {}

    private static Deal map(ResultSet rs) throws SQLException {
        return new Deal(rs.getObject("id", UUID.class), rs.getObject("contact_id", UUID.class), rs.getString("contact_name"), rs.getString("company_name"),
                rs.getString("title"), rs.getString("stage"), rs.getDouble("position"), rs.getObject("value_minor", Long.class), rs.getString("currency"),
                rs.getObject("expected_close_on", LocalDate.class), rs.getString("notes"), rs.getString("lost_reason"),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static final String SELECT = """
            SELECT d.*, c.name AS contact_name FROM deals d LEFT JOIN contacts c ON c.id = d.contact_id
            """;

    private UUID org() {
        UUID org = TenantContext.current().orgId();
        entitlements.require(org, EntitlementService.PIPELINE);
        return org;
    }

    @GetMapping
    @RequiresPermission(Permission.CONTACTS_READ)
    public List<Column> board() {
        UUID org = org();
        List<Column> out = new ArrayList<>();
        for (String st : STAGES) {
            var p = new MapSqlParameterSource("org", org).addValue("stage", st);
            var deals = named.query(SELECT + " WHERE d.organization_id = :org AND d.stage = :stage ORDER BY d.position, d.created_at LIMIT 200", p, (rs, i) -> map(rs));
            var agg = named.queryForMap("SELECT count(*) AS n, COALESCE(sum(value_minor), 0) AS v FROM deals WHERE organization_id = :org AND stage = :stage", p);
            out.add(new Column(st, ((Number) agg.get("n")).longValue(), ((Number) agg.get("v")).longValue(), deals));
        }
        return out;
    }

    @PostMapping
    @RequiresPermission(Permission.CONTACTS_WRITE)
    @Transactional
    public Deal create(@Valid @RequestBody DealInput in) {
        UUID org = org();
        checkContact(org, in.contactId());
        UUID id = Ids.newId();
        Double last = named.queryForObject("SELECT max(position) FROM deals WHERE organization_id = :org AND stage = 'LEAD'", new MapSqlParameterSource("org", org), Double.class);
        named.update("""
                INSERT INTO deals (id, organization_id, contact_id, company_name, title, stage, position, value_minor, currency, expected_close_on, notes, owner_user_id)
                VALUES (:id, :org, :contact, :company, :title, 'LEAD', :pos, :value, :currency, :close, :notes, :owner)""",
                new MapSqlParameterSource("id", id).addValue("org", org).addValue("contact", in.contactId()).addValue("company", blank(in.companyName()))
                        .addValue("title", in.title().trim()).addValue("pos", last == null ? 1000 : last + 1000).addValue("value", in.valueMinor())
                        .addValue("currency", in.currency()).addValue("close", in.expectedCloseOn()).addValue("notes", blank(in.notes()))
                        .addValue("owner", TenantContext.current().userId()));
        return get(org, id);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public Deal update(@PathVariable UUID id, @Valid @RequestBody DealInput in) {
        UUID org = org();
        checkContact(org, in.contactId());
        int n = named.update("""
                UPDATE deals SET contact_id = :contact, company_name = :company, title = :title, value_minor = :value, currency = :currency,
                  expected_close_on = :close, notes = :notes, updated_at = now() WHERE id = :id AND organization_id = :org""",
                new MapSqlParameterSource("id", id).addValue("org", org).addValue("contact", in.contactId()).addValue("company", blank(in.companyName()))
                        .addValue("title", in.title().trim()).addValue("value", in.valueMinor()).addValue("currency", in.currency())
                        .addValue("close", in.expectedCloseOn()).addValue("notes", blank(in.notes())));
        if (n == 0) throw ApiException.notFound("Deal");
        return get(org, id);
    }

    /** Drag & drop: put the card in {@code stage} at {@code index} (0 = top; null = bottom). */
    @PostMapping("/{id}/move")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    @Transactional
    public Deal move(@PathVariable UUID id, @Valid @RequestBody Move m) {
        UUID org = org();
        if (!STAGES.contains(m.stage())) throw ApiException.badRequest("Unknown stage");
        get(org, id);
        var p = new MapSqlParameterSource("org", org).addValue("stage", m.stage()).addValue("id", id);
        List<Double> positions = named.queryForList("SELECT position FROM deals WHERE organization_id = :org AND stage = :stage AND id <> :id ORDER BY position, created_at", p, Double.class);
        int idx = m.index() == null ? positions.size() : Math.max(0, Math.min(m.index(), positions.size()));
        double before = idx == 0 ? (positions.isEmpty() ? 0 : positions.getFirst() - 2000) : positions.get(idx - 1);
        double after = idx >= positions.size() ? before + 2000 : positions.get(idx);
        double pos = (before + after) / 2;
        boolean closed = m.stage().equals("WON") || m.stage().equals("LOST");
        named.update("""
                UPDATE deals SET stage = :stage, position = :pos, updated_at = now(),
                  closed_at = CASE WHEN :closed THEN COALESCE(closed_at, now()) ELSE NULL END,
                  lost_reason = CASE WHEN :stage = 'LOST' THEN COALESCE(:reason, lost_reason) ELSE NULL END
                WHERE id = :id AND organization_id = :org""", p.addValue("pos", pos).addValue("closed", closed).addValue("reason", blank(m.lostReason())));
        return get(org, id);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.CONTACTS_WRITE)
    public void delete(@PathVariable UUID id) {
        UUID org = org();
        if (named.update("DELETE FROM deals WHERE id = :id AND organization_id = :org", new MapSqlParameterSource("id", id).addValue("org", org)) == 0) {
            throw ApiException.notFound("Deal");
        }
    }

    private Deal get(UUID org, UUID id) {
        return named.query(SELECT + " WHERE d.id = :id AND d.organization_id = :org", new MapSqlParameterSource("id", id).addValue("org", org), (rs, i) -> map(rs))
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Deal"));
    }

    private void checkContact(UUID org, UUID contact) {
        if (contact == null) return;
        Long n = named.queryForObject("SELECT count(*) FROM contacts WHERE id = :id AND organization_id = :org",
                new MapSqlParameterSource("id", contact).addValue("org", org), Long.class);
        if (n == null || n == 0) throw ApiException.notFound("Contact");
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
