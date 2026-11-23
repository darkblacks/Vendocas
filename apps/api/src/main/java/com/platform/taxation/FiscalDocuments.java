package com.platform.taxation;

import com.platform.audit.AuditService;
import com.platform.operations.PlatformSettings;
import com.platform.operations.PlatformSettingsService;
import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.jobs.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Fiscal obligations of paid invoices. Driven by billing events, never by Billing calling in.
 *
 * <p>Which document an invoice needs is a legal question (here: Brazilian seller selling to a Brazilian customer →
 * NFS-e). Whether the platform issues it <em>automatically</em> is an operational switch. With automation off the
 * obligation is still recorded as {@code AWAITING_MANUAL} — disabling automation never waives the obligation.
 */
@Service
public class FiscalDocuments {
    public static final String TYPE_BR_NFSE = "BR_NFSE";
    private final JdbcTemplate jdbc;
    private final PlatformSettingsService settings;
    private final FiscalDocumentProvider provider;
    private final AppProperties props;
    private final JobService jobs;
    private final AuditService audit;
    private final Clock clock;

    public FiscalDocuments(JdbcTemplate jdbc, PlatformSettingsService settings, FiscalDocumentProvider provider, AppProperties props,
                           JobService jobs, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.provider = provider;
        this.props = props;
        this.jobs = jobs;
        this.audit = audit;
        this.clock = clock;
    }

    /** Document type required for a paid invoice, if any (pure legal rule — independent of any switch). */
    public Optional<String> requiredDocument(UUID invoiceId) {
        List<String> buyer = jdbc.queryForList("""
                SELECT o.billing_country_code FROM invoices i JOIN organizations o ON o.id = i.organization_id WHERE i.id = ?""",
                String.class, invoiceId);
        if (buyer.isEmpty()) return Optional.empty();
        boolean brazilianSale = "BR".equals(props.seller() == null ? null : props.seller().countryCode()) && "BR".equals(buyer.getFirst());
        return brazilianSale ? Optional.of(TYPE_BR_NFSE) : Optional.empty();
    }

    public static String reference(UUID invoiceId) { return "vd-" + invoiceId; }

    /** Initial status for a newly paid invoice under the current settings. */
    String initialStatus(PlatformSettings s) {
        if (!s.fiscalAutomation()) return "AWAITING_MANUAL";
        if (s.fiscalProvider() == PlatformSettings.FiscalProvider.NONE || !provider.configured()) return "NOT_CONFIGURED";
        return "PENDING";
    }

    /**
     * Records the fiscal obligation of a paid invoice (idempotent per invoice) and, only when automatic issuance is
     * active and the provider is configured, schedules issuance. Returns the resulting status, or empty when no fiscal
     * document is required.
     */
    @Transactional
    public Optional<String> register(UUID organizationId, UUID invoiceId, UUID paymentId) {
        Optional<String> type = requiredDocument(invoiceId);
        if (type.isEmpty()) return Optional.empty();
        PlatformSettings s = settings.current();
        String status = initialStatus(s);
        String message = switch (status) {
            case "AWAITING_MANUAL" -> "Emissão automática desativada: emitir manualmente";
            case "NOT_CONFIGURED" -> s.fiscalProvider() == PlatformSettings.FiscalProvider.NONE
                    ? "Emissão automática ativada sem provedor fiscal selecionado" : "Provedor fiscal sem credencial (FOCUS_NFE_TOKEN)";
            default -> null;
        };
        int inserted = jdbc.update("""
                INSERT INTO tax_documents (id, organization_id, invoice_id, payment_id, document_type, status, provider, provider_ref, error_message)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (invoice_id, document_type) DO NOTHING""",
                Ids.newId(), organizationId, invoiceId, paymentId, type.get(), status,
                s.fiscalProvider() == PlatformSettings.FiscalProvider.NONE ? "NONE" : provider.name(), reference(invoiceId), message);
        if (inserted == 1 && "PENDING".equals(status)) scheduleIssue(organizationId, invoiceId, false);
        return Optional.of(jdbc.queryForObject("SELECT status FROM tax_documents WHERE invoice_id = ? AND document_type = ?",
                String.class, invoiceId, type.get()));
    }

    void scheduleIssue(UUID organizationId, UUID invoiceId, boolean manual) {
        jobs.schedule("fiscal.issue", "fiscal:" + invoiceId + (manual ? ":manual:" + clock.millis() : ""), organizationId,
                Map.of("invoiceId", invoiceId.toString(), "manual", manual), clock.instant());
    }

    /** A refund never cancels fiscal documents automatically: it flags them for review by the operator. */
    @Transactional
    public void paymentRefunded(UUID invoiceId, boolean full) {
        if (invoiceId == null) return;
        jdbc.update("""
                UPDATE tax_documents SET needs_review = true, review_reason = ?, updated_at = now()
                WHERE invoice_id = ? AND status <> 'CANCELED'""",
                full ? "Pagamento estornado integralmente: avaliar cancelamento ou nota de ajuste" : "Pagamento estornado parcialmente: avaliar ajuste",
                invoiceId);
    }

    // ------------------------------------------------------------------ manual / later issuance (operator actions)

    /** Issue now through the selected provider, even with automation off (e.g. backlog after enabling Focus NFe). */
    @Transactional
    public String requestIssue(UUID invoiceId, UUID actorUserId) {
        var doc = document(invoiceId);
        PlatformSettings s = settings.current();
        if (s.fiscalProvider() == PlatformSettings.FiscalProvider.NONE) throw new ApiException(ErrorCode.INVALID_STATE, "No fiscal provider selected; record the document as issued externally instead");
        if (!provider.configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Fiscal provider credentials are not configured (FOCUS_NFE_TOKEN)");
        String status = (String) doc.get("status");
        if ("ISSUED".equals(status) || "PROCESSING".equals(status) || "CANCELED".equals(status))
            throw new ApiException(ErrorCode.INVALID_STATE, "Document is already " + status);
        jdbc.update("UPDATE tax_documents SET status='PENDING', provider=?, error_message=NULL, updated_at=now() WHERE id=?", provider.name(), doc.get("id"));
        UUID org = (UUID) doc.get("organization_id");
        scheduleIssue(org, invoiceId, true);
        audit.record(org, actorUserId, "PLATFORM_ADMIN", "fiscal.issue_requested", "invoice", invoiceId, Map.of(), null);
        return "PENDING";
    }

    /** The document was issued outside the platform (e.g. in the city hall portal): keep the record complete. */
    @Transactional
    public void recordExternalIssue(UUID invoiceId, String number, Instant issuedAt, String notes, UUID actorUserId) {
        var doc = document(invoiceId);
        if ("ISSUED".equals(doc.get("status"))) throw new ApiException(ErrorCode.INVALID_STATE, "Document already issued");
        jdbc.update("""
                UPDATE tax_documents SET status='ISSUED', provider='MANUAL', number=?, issued_at=?, notes=?, error_message=NULL, updated_at=now()
                WHERE id=?""", number, Timestamp.from(issuedAt == null ? clock.instant() : issuedAt), notes, doc.get("id"));
        audit.record((UUID) doc.get("organization_id"), actorUserId, "PLATFORM_ADMIN", "fiscal.recorded_external", "invoice", invoiceId,
                Map.of("number", number), null);
    }

    private Map<String, Object> document(UUID invoiceId) {
        var rows = jdbc.queryForList("SELECT id, organization_id, status FROM tax_documents WHERE invoice_id = ? AND document_type = ?", invoiceId, TYPE_BR_NFSE);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "This invoice has no fiscal obligation recorded");
        return rows.getFirst();
    }
}
