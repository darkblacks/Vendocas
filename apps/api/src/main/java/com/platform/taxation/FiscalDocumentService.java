package com.platform.taxation;

import com.platform.operations.PlatformSettings;
import com.platform.operations.PlatformSettingsService;
import com.platform.shared.AppProperties;
import com.platform.shared.jobs.Job;
import com.platform.shared.jobs.JobHandler;
import com.platform.shared.jobs.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Issues a fiscal document through the provider (job {@code fiscal.issue}). The obligation itself is recorded by
 * {@link FiscalDocuments#register} from billing events; this job only talks to the provider, and only when allowed:
 * <ul>
 *   <li>automatic job → requires fiscal automation ON and a selected, configured provider; otherwise the document is
 *       left as AWAITING_MANUAL / NOT_CONFIGURED and the provider is not called;</li>
 *   <li>manual job (operator "issue now") → requires a selected, configured provider (automation may be off).</li>
 * </ul>
 * No provider configured → never a fake number.
 */
@Component
public class FiscalDocumentService implements JobHandler {
    private final JdbcTemplate jdbc;
    private final FiscalDocumentProvider provider;
    private final AppProperties props;
    private final JobService jobs;
    private final Clock clock;
    private final PlatformSettingsService settings;
    private final FiscalDocuments documents;

    public FiscalDocumentService(JdbcTemplate jdbc, FiscalDocumentProvider provider, AppProperties props, JobService jobs, Clock clock,
                                 PlatformSettingsService settings, FiscalDocuments documents) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.props = props;
        this.jobs = jobs;
        this.clock = clock;
        this.settings = settings;
        this.documents = documents;
    }

    @Override public String type() { return "fiscal.issue"; }

    @Override
    public void handle(Job job) {
        UUID invoiceId = UUID.fromString(job.payload().path("invoiceId").asString());
        boolean manual = job.payload().path("manual").asBoolean(false);
        var inv = jdbc.queryForMap("""
                SELECT i.id, i.organization_id, i.status, i.total_minor, i.currency, i.number, o.legal_name, o.billing_country_code,
                       COALESCE(o.finance_email, o.contact_email)::text AS email, o.address_line1, o.city, o.region, o.postal_code,
                       t.tax_id_type, t.tax_id
                FROM invoices i JOIN organizations o ON o.id = i.organization_id LEFT JOIN tax_profiles t ON t.organization_id = o.id
                WHERE i.id = ?""", invoiceId);
        if (!"PAID".equals(inv.get("status"))) return; // only paid invoices get fiscal documents
        UUID orgId = (UUID) inv.get("organization_id");
        // Jobs queued before the billing/fiscal split have no obligation row yet: record it the same way events do.
        if (documents.requiredDocument(invoiceId).isEmpty()) return;
        documents.register(orgId, invoiceId, null);
        String ref = FiscalDocuments.reference(invoiceId);
        String current = jdbc.queryForObject("SELECT status FROM tax_documents WHERE provider_ref = ?", String.class, ref);
        if ("ISSUED".equals(current) || "CANCELED".equals(current)) return;

        PlatformSettings s = settings.current();
        boolean providerSelected = s.fiscalProvider() != PlatformSettings.FiscalProvider.NONE;
        if (!manual && !s.automaticIssuance()) {
            // Automation was switched off after this job was queued: do not call the provider.
            jdbc.update("UPDATE tax_documents SET status = CASE WHEN status IN ('PENDING','NOT_CONFIGURED') THEN 'AWAITING_MANUAL' ELSE status END, updated_at=now() WHERE provider_ref=?", ref);
            return;
        }
        if (!providerSelected || !provider.configured()) {
            jdbc.update("UPDATE tax_documents SET status='NOT_CONFIGURED', error_message=?, updated_at=now() WHERE provider_ref=? AND status <> 'PROCESSING'",
                    providerSelected ? "Provedor fiscal sem credencial (FOCUS_NFE_TOKEN)" : "Nenhum provedor fiscal selecionado", ref);
            return;
        }
        if (!"BRL".equals(inv.get("currency"))) {
            jdbc.update("UPDATE tax_documents SET status='REJECTED', error_message=?, updated_at=now() WHERE provider_ref=?",
                    "NFS-e requires a BRL amount; configure BRL prices for Brazilian customers", ref);
            return;
        }
        FiscalDocumentProvider.Status st = "PROCESSING".equals(current)
                ? provider.query(ref)
                : provider.submit(new FiscalDocumentProvider.NfseRequest(ref,
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now(clock).atZone(ZoneId.of("America/Sao_Paulo"))),
                        props.seller().taxId(), props.seller().municipalRegistration(), props.seller().municipalityCode(),
                        props.seller().serviceCode(), "Assinatura mensal Vendocas - fatura " + inv.get("number"),
                        ((Number) inv.get("total_minor")).longValue(), (String) inv.get("tax_id_type"), (String) inv.get("tax_id"),
                        (String) inv.get("legal_name"), (String) inv.get("email"), (String) inv.get("address_line1"), (String) inv.get("city"),
                        (String) inv.get("region"), (String) inv.get("postal_code")));
        jdbc.update("""
                UPDATE tax_documents SET status=?, provider=?, number=?, verification_code=?, document_object_key=?, xml_object_key=?, error_message=?,
                  issued_at = CASE WHEN ?='ISSUED' THEN now() ELSE issued_at END, updated_at=now() WHERE provider_ref=?""",
                st.status(), provider.name(), st.number(), st.verificationCode(), st.pdfUrl(), st.xmlUrl(), st.error(), st.status(), ref);
        if ("PROCESSING".equals(st.status())) {
            // NFS-e authorization is asynchronous at most municipalities: poll again (keeps the manual flag).
            jobs.schedule("fiscal.issue", "fiscal:" + invoiceId + ":poll:" + (job.attempts() + clock.millis() / 60000), orgId,
                    Map.of("invoiceId", invoiceId.toString(), "manual", manual), clock.instant().plusSeconds(120));
        }
    }
}
