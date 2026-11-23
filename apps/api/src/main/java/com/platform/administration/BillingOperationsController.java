package com.platform.administration;

import com.platform.operations.PlatformSettings;
import com.platform.operations.PlatformSettingsService;
import com.platform.security.CurrentUser;
import com.platform.taxation.FiscalDocuments;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Platform operations for environment, payments and fiscal documents (guarded by PlatformAdminInterceptor: platform
 * role + MFA; mutations require PLATFORM_ADMIN). New endpoints only — existing billing contracts are unchanged.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin")
public class BillingOperationsController {
    private final PlatformSettingsService settings;
    private final FiscalDocuments fiscal;
    private final JdbcTemplate jdbc;

    public BillingOperationsController(PlatformSettingsService settings, FiscalDocuments fiscal, JdbcTemplate jdbc) {
        this.settings = settings;
        this.fiscal = fiscal;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/platform-settings")
    public PlatformSettingsService.Status platformSettings() { return settings.status(); }

    public record SettingsRequest(PlatformSettings.Environment environment, PlatformSettings.StripeMode stripeMode, Boolean fiscalAutomation,
                                  PlatformSettings.FiscalProvider fiscalProvider, Integer expectedVersion) {}

    @PutMapping("/platform-settings")
    public PlatformSettingsService.Status updatePlatformSettings(@RequestBody SettingsRequest r) {
        settings.update(new PlatformSettingsService.Update(r.environment(), r.stripeMode(), r.fiscalAutomation(), r.fiscalProvider(), r.expectedVersion()),
                "admin:" + CurrentUser.jwt().getClaimAsString("email"), "PLATFORM_ADMIN", CurrentUser.id());
        return settings.status();
    }

    // ------------------------------------------------------------------ payments: consultation, export, reconciliation

    static final String PAYMENTS_SQL = """
            SELECT p.id, p.organization_id, o.legal_name, p.status, p.currency, p.amount_minor, p.refunded_minor, p.provider, p.provider_payment_id,
                   p.settled_at, p.livemode, p.environment, i.number AS invoice_number, i.provider_invoice_id,
                   t.document_type AS fiscal_document_type, t.status AS fiscal_status, t.number AS fiscal_number, t.needs_review AS fiscal_needs_review
            FROM payments p JOIN organizations o ON o.id = p.organization_id
            LEFT JOIN invoices i ON i.id = p.invoice_id
            LEFT JOIN tax_documents t ON t.invoice_id = p.invoice_id
            WHERE p.created_at >= ? AND p.created_at < ?
              AND (?::text IS NULL OR p.status = ?) AND (?::text IS NULL OR t.status = ?)
            ORDER BY p.created_at DESC LIMIT 5000""";

    @GetMapping("/billing/payments")
    public ResponseEntity<?> payments(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                      @RequestParam(required = false) String status, @RequestParam(required = false) String fiscalStatus,
                                      @RequestParam(defaultValue = "json") String format) {
        Instant end = to == null ? Instant.now().plus(1, ChronoUnit.DAYS) : to;
        Instant start = from == null ? end.minus(31, ChronoUnit.DAYS) : from;
        List<Map<String, Object>> rows = jdbc.queryForList(PAYMENTS_SQL, Timestamp.from(start), Timestamp.from(end), status, status, fiscalStatus, fiscalStatus);
        if (!"csv".equalsIgnoreCase(format)) return ResponseEntity.ok(rows);
        String[] cols = {"id", "organization_id", "legal_name", "status", "currency", "amount_minor", "refunded_minor", "provider", "provider_payment_id",
                "settled_at", "livemode", "environment", "invoice_number", "provider_invoice_id", "fiscal_document_type", "fiscal_status",
                "fiscal_number", "fiscal_needs_review"};
        StringBuilder csv = new StringBuilder(String.join(",", cols)).append('\n');
        for (var row : rows) {
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) csv.append(',');
                csv.append(csvCell(row.get(cols[i])));
            }
            csv.append('\n');
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payments.csv\"").body(csv.toString());
    }

    /** RFC 4180 quoting, plus a leading quote for values a spreadsheet would evaluate as a formula. */
    static String csvCell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        boolean quote = s.indexOf('"') >= 0 || s.indexOf(',') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.startsWith("'");
        return quote ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ------------------------------------------------------------------ fiscal documents (manual / later issuance)

    @GetMapping("/fiscal/documents")
    public List<Map<String, Object>> fiscalDocuments(@RequestParam(required = false) String status) {
        return jdbc.queryForList("""
                SELECT t.id, t.organization_id, o.legal_name, t.invoice_id, i.number AS invoice_number, i.total_minor, i.currency, i.paid_at,
                       t.payment_id, t.document_type, t.status, t.provider, t.number, t.issued_at, t.error_message, t.needs_review, t.review_reason,
                       t.notes, t.created_at, t.updated_at
                FROM tax_documents t JOIN invoices i ON i.id = t.invoice_id JOIN organizations o ON o.id = t.organization_id
                WHERE (?::text IS NULL OR t.status = ?) ORDER BY t.created_at DESC LIMIT 1000""", status, status);
    }

    @PostMapping("/fiscal/invoices/{invoiceId}/issue")
    public Map<String, String> issueNow(@PathVariable UUID invoiceId) {
        return Map.of("status", fiscal.requestIssue(invoiceId, CurrentUser.id()));
    }

    public record ExternalIssue(@NotBlank @Size(max = 60) String number, Instant issuedAt, @Size(max = 1000) String notes) {}

    @PostMapping("/fiscal/invoices/{invoiceId}/record-external")
    public Map<String, String> recordExternal(@PathVariable UUID invoiceId, @Valid @RequestBody ExternalIssue r) {
        fiscal.recordExternalIssue(invoiceId, r.number(), r.issuedAt(), r.notes(), CurrentUser.id());
        return Map.of("status", "ISSUED");
    }
}
