package com.platform.taxation;

import com.platform.shared.AppProperties;
import com.platform.shared.Ids;
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
 * Decides which fiscal document a PAID invoice needs and issues it. Rules:
 * <ul>
 *   <li>Seller is a Brazilian entity and buyer is in Brazil → NFS-e via the configured provider.</li>
 *   <li>Seller not Brazilian → no NFS-e; the provider's invoice (with tax computed by Stripe Tax) is
 *       the billing document. EU national e-invoicing mandates are tracked as NOT_CONFIGURED.</li>
 *   <li>No provider configured → document recorded as NOT_CONFIGURED (never a fake number).</li>
 * </ul>
 */
@Component
public class FiscalDocumentService implements JobHandler {
    private final JdbcTemplate jdbc;
    private final FiscalDocumentProvider provider;
    private final AppProperties props;
    private final JobService jobs;
    private final Clock clock;

    public FiscalDocumentService(JdbcTemplate jdbc, FiscalDocumentProvider provider, AppProperties props, JobService jobs, Clock clock) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.props = props;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override public String type() { return "fiscal.issue"; }

    @Override
    public void handle(Job job) {
        UUID invoiceId = UUID.fromString(job.payload().path("invoiceId").asString());
        var inv = jdbc.queryForMap("""
                SELECT i.id, i.organization_id, i.status, i.total_minor, i.currency, i.number, o.legal_name, o.billing_country_code,
                       COALESCE(o.finance_email, o.contact_email) AS email, o.address_line1, o.city, o.region, o.postal_code,
                       t.tax_id_type, t.tax_id
                FROM invoices i JOIN organizations o ON o.id = i.organization_id JOIN tax_profiles t ON t.organization_id = o.id
                WHERE i.id = ?""", invoiceId);
        if (!"PAID".equals(inv.get("status"))) return; // only paid invoices get fiscal documents
        String sellerCountry = props.seller().countryCode();
        boolean brazilianSale = "BR".equals(sellerCountry) && "BR".equals(inv.get("billing_country_code"));
        if (!brazilianSale) return;

        UUID orgId = (UUID) inv.get("organization_id");
        String ref = "vd-" + invoiceId;
        jdbc.update("""
                INSERT INTO tax_documents (id, organization_id, invoice_id, document_type, status, provider, provider_ref)
                VALUES (?,?,?,'BR_NFSE','PENDING',?,?) ON CONFLICT (invoice_id, document_type) DO NOTHING""",
                Ids.newId(), orgId, invoiceId, provider.name(), ref);
        String current = jdbc.queryForObject("SELECT status FROM tax_documents WHERE provider_ref = ?", String.class, ref);
        if ("ISSUED".equals(current) || "CANCELED".equals(current)) return;
        if (!provider.configured()) {
            jdbc.update("UPDATE tax_documents SET status='NOT_CONFIGURED', error_message=?, updated_at=now() WHERE provider_ref=?",
                    "NFS-e provider credentials not configured; issue manually or configure FOCUS_NFE_TOKEN", ref);
            return;
        }
        if (!"BRL".equals(inv.get("currency"))) {
            jdbc.update("UPDATE tax_documents SET status='REJECTED', error_message=?, updated_at=now() WHERE provider_ref=?",
                    "NFS-e requires a BRL amount; configure BRL prices for Brazilian customers", ref);
            return;
        }
        FiscalDocumentProvider.Status st = "PENDING".equals(current) || "REJECTED".equals(current)
                ? provider.submit(new FiscalDocumentProvider.NfseRequest(ref,
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.now(clock).atZone(ZoneId.of("America/Sao_Paulo"))),
                        props.seller().taxId(), props.seller().municipalRegistration(), props.seller().municipalityCode(),
                        props.seller().serviceCode(), "Assinatura mensal Vendocas - fatura " + inv.get("number"),
                        ((Number) inv.get("total_minor")).longValue(), (String) inv.get("tax_id_type"), (String) inv.get("tax_id"),
                        (String) inv.get("legal_name"), (String) inv.get("email"), (String) inv.get("address_line1"), (String) inv.get("city"),
                        (String) inv.get("region"), (String) inv.get("postal_code")))
                : provider.query(ref);
        jdbc.update("""
                UPDATE tax_documents SET status=?, number=?, verification_code=?, document_object_key=?, xml_object_key=?, error_message=?,
                  issued_at = CASE WHEN ?='ISSUED' THEN now() ELSE issued_at END, updated_at=now() WHERE provider_ref=?""",
                st.status(), st.number(), st.verificationCode(), st.pdfUrl(), st.xmlUrl(), st.error(), st.status(), ref);
        if ("PROCESSING".equals(st.status())) {
            // NFS-e authorization is asynchronous at most municipalities: poll again.
            jobs.schedule("fiscal.issue", "fiscal:" + invoiceId + ":poll:" + (job.attempts() + clock.millis() / 60000), orgId,
                    Map.of("invoiceId", invoiceId.toString()), clock.instant().plusSeconds(120));
        }
    }
}
