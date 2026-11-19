package com.platform.contacts;

import com.platform.audit.AuditService;
import com.platform.consent.ConsentService;
import com.platform.contacts.ContactDtos.ContactInput;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.api.ApiException;
import com.platform.shared.jobs.Job;
import com.platform.shared.jobs.JobHandler;
import com.platform.shared.jobs.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Contact import: upload → map columns → preview (validation, duplicates) → commit (async job) → optional rollback.
 * Consent is NOT assumed: contacts are imported without consent unless the importer explicitly declares
 * the channel, purpose, legal basis and how it was collected — recorded as IMPORT_DECLARED evidence.
 */
@Service
public class ImportService implements JobHandler {
    public static final Set<String> FIELDS = Set.of("external_id", "name", "email", "phone", "locale", "country_code", "time_zone",
            "birth_date", "last_interaction_at", "last_purchase_at", "last_visit_at", "next_appointment_at", "lifetime_value", "tags", "ignore");
    private final JdbcTemplate jdbc;
    private final ContactService contacts;
    private final ConsentService consents;
    private final JobService jobs;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ImportService(JdbcTemplate jdbc, ContactService contacts, ConsentService consents, JobService jobs, AuditService audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.consents = consents;
        this.jobs = jobs;
        this.audit = audit;
        this.tx = tx;
    }

    public record UploadResult(UUID importId, List<String> headers, List<List<String>> sample, Map<String, String> suggestedMapping, int totalRows) {}

    public record DeclaredConsent(List<String> channels, String purpose, String legalBasis, String evidence) {}

    public record MappingRequest(Map<String, String> mapping, DeclaredConsent declaredConsent, List<String> tags) {}

    public record RowError(int row, String error) {}

    public record Preview(int totalRows, int validRows, int invalidRows, int duplicateInFile, int matchingExisting, List<RowError> errors,
                          List<Map<String, String>> sample) {}

    public record ImportView(UUID id, String fileName, String status, int totalRows, int createdRows, int updatedRows, int skippedRows,
                             int errorRows, Instant createdAt, Instant completedAt) {}

    @Transactional
    public UploadResult upload(UUID orgId, UUID userId, String fileName, byte[] content) {
        String format = fileName.toLowerCase().endsWith(".xlsx") ? "XLSX" : fileName.toLowerCase().endsWith(".csv") ? "CSV" : null;
        if (format == null) throw ApiException.badRequest("Only .csv and .xlsx files are supported");
        if ("XLSX".equals(format) && !(content.length > 4 && content[0] == 'P' && content[1] == 'K')) throw ApiException.badRequest("Invalid XLSX file");
        ImportParser.Table t = ImportParser.parse(content, format);
        UUID id = Ids.newId();
        int retention = jdbc.queryForObject("SELECT import_file_retention_days FROM organization_settings WHERE organization_id = ?", Integer.class, orgId);
        jdbc.update("""
                INSERT INTO contact_imports (id, organization_id, file_name, file_content, format, status, total_rows, created_by, file_expires_at)
                VALUES (?,?,?,?,?,'UPLOADED',?,?, now() + make_interval(days => ?))""",
                id, orgId, fileName.length() > 255 ? fileName.substring(0, 255) : fileName, content, format, t.rows().size(), userId, retention);
        return new UploadResult(id, t.headers(), t.rows().stream().limit(5).toList(), suggest(t.headers()), t.rows().size());
    }

    static Map<String, String> suggest(List<String> headers) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String h : headers) {
            String n = h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            String f = n.matches(".*(email|mail|correo).*") ? "email"
                    : n.matches(".*(phone|celular|telefone|telefono|mobile|whatsapp|fone).*") ? "phone"
                    : n.matches(".*(nome|name|nombre|cliente).*") ? "name"
                    : n.matches(".*(ultimacompra|lastpurchase|ultimacompra).*") ? "last_purchase_at"
                    : n.matches(".*(ultimavisita|lastvisit).*") ? "last_visit_at"
                    : n.matches(".*(nascimento|birth|aniversario|cumpleanos).*") ? "birth_date"
                    : n.matches(".*(id|codigo|code)") ? "external_id"
                    : n.matches(".*(tag|etiqueta).*") ? "tags"
                    : n.matches(".*(pais|country).*") ? "country_code"
                    : "ignore";
            m.put(h, f);
        }
        return m;
    }

    @Transactional
    public Preview map(UUID orgId, UUID importId, MappingRequest req) {
        var imp = load(orgId, importId);
        if (!Set.of("UPLOADED", "MAPPED", "PREVIEWED").contains((String) imp.get("status"))) throw ApiException.invalidState("Import already processed");
        Set<String> customKeys = new HashSet<>(jdbc.queryForList("SELECT key FROM contact_custom_fields WHERE organization_id = ?", String.class, orgId));
        for (String f : req.mapping().values()) {
            if (!FIELDS.contains(f) && !(f.startsWith("custom.") && customKeys.contains(f.substring(7)))) throw ApiException.badRequest("Unknown target field: " + f);
        }
        if (!req.mapping().containsValue("email") && !req.mapping().containsValue("phone")) throw ApiException.badRequest("Map at least an email or a phone column");
        DeclaredConsent dc = req.declaredConsent();
        if (dc != null) {
            if (dc.evidence() == null || dc.evidence().isBlank()) throw ApiException.badRequest("Describe how consent was collected");
            if (!ConsentService.BASES.contains(dc.legalBasis()) || !ConsentService.PURPOSES.contains(dc.purpose())) throw ApiException.badRequest("Invalid consent declaration");
        }
        jdbc.update("UPDATE contact_imports SET column_mapping = ?::jsonb, declared_consent = ?::jsonb, status = 'PREVIEWED' WHERE id = ?",
                Json.write(Map.of("mapping", req.mapping(), "tags", req.tags() == null ? List.of() : req.tags())),
                dc == null ? null : Json.write(dc), importId);
        return preview(orgId, imp, req.mapping());
    }

    private Preview preview(UUID orgId, Map<String, Object> imp, Map<String, String> mapping) {
        ImportParser.Table t = ImportParser.parse((byte[]) imp.get("file_content"), (String) imp.get("format"));
        Ctx ctx = ctx(orgId);
        List<RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int valid = 0, dupes = 0, existing = 0;
        List<Map<String, String>> sample = new ArrayList<>();
        for (int i = 0; i < t.rows().size(); i++) {
            try {
                ContactInput in = toInput(t.headers(), t.rows().get(i), mapping, ctx, List.of());
                String key = in.email() != null ? in.email().toLowerCase() : Phones.toE164(in.phone(), ctx.country).orElse(in.phone());
                if (in.email() == null && Phones.toE164(in.phone(), ctx.country).isEmpty()) throw new IllegalArgumentException("invalid phone");
                if (!seen.add(key)) { dupes++; continue; }
                Integer ex = jdbc.queryForObject("SELECT count(*) FROM contacts WHERE organization_id = ? AND status <> 'ERASED' AND (email = ? OR phone_e164 = ?)",
                        Integer.class, orgId, in.email(), Phones.toE164(in.phone(), ctx.country).orElse(null));
                if (ex != null && ex > 0) existing++;
                valid++;
                if (sample.size() < 10) sample.add(Map.of("name", nz(in.name()), "email", nz(in.email()), "phone", nz(in.phone())));
            } catch (RuntimeException e) {
                if (errors.size() < 200) errors.add(new RowError(i + 2, e.getMessage()));
            }
        }
        return new Preview(t.rows().size(), valid, t.rows().size() - valid - dupes, dupes, existing, errors, sample);
    }

    @Transactional
    public void commit(UUID orgId, UUID userId, UUID importId) {
        var imp = load(orgId, importId);
        if (!"PREVIEWED".equals(imp.get("status"))) throw ApiException.invalidState("Map and preview the file first");
        jdbc.update("UPDATE contact_imports SET status = 'PROCESSING' WHERE id = ?", importId);
        jobs.schedule("contacts.import", "import:" + importId, orgId, Map.of("importId", importId.toString()), Instant.now());
        audit.user(orgId, userId, "contacts.import_committed", "import", importId, Map.of());
    }

    @Override public String type() { return "contacts.import"; }

    /** Each row is upserted in its own transaction so one bad row never aborts the whole file. */
    @Override
    public void handle(Job job) {
        UUID orgId = job.organizationId();
        UUID importId = UUID.fromString(job.payload().path("importId").asString());
        var imp = load(orgId, importId);
        if (!"PROCESSING".equals(imp.get("status"))) return;
        JsonNode cfg = Json.read((String) imp.get("column_mapping"));
        Map<String, String> mapping = new LinkedHashMap<>();
        cfg.path("mapping").properties().forEach(e -> mapping.put(e.getKey(), e.getValue().asString()));
        List<String> tags = new ArrayList<>();
        cfg.path("tags").forEach(n -> tags.add(n.asString()));
        tags.add("import:" + importId.toString().substring(0, 8));
        DeclaredConsent dc = imp.get("declared_consent") == null ? null : Json.read((String) imp.get("declared_consent"), DeclaredConsent.class);
        ImportParser.Table t = ImportParser.parse((byte[]) imp.get("file_content"), (String) imp.get("format"));
        Ctx ctx = ctx(orgId);
        int created = 0, updated = 0, failed = 0;
        List<RowError> errors = new ArrayList<>();
        UUID createdBy = (UUID) imp.get("created_by");
        for (int i = 0; i < t.rows().size(); i++) {
            final int rowNo = i + 2;
            final List<String> row = t.rows().get(i);
            try {
                Boolean isNew = tx.execute(s -> {
                    ContactInput in = toInput(t.headers(), row, mapping, ctx, tags);
                    var res = contacts.upsert(orgId, in, "IMPORT", importId.toString());
                    if (dc != null) {
                        var ev = new ConsentService.Evidence("IMPORT_DECLARED", "user:" + createdBy, null, null, dc.evidence(), null, Instant.now());
                        for (String ch : dc.channels()) consents.set(orgId, res.getKey().id(), ch, dc.purpose(), "GRANTED", dc.legalBasis(), ev);
                    }
                    return res.getValue();
                });
                if (Boolean.TRUE.equals(isNew)) created++; else updated++;
            } catch (RuntimeException e) {
                failed++;
                if (errors.size() < 500) errors.add(new RowError(rowNo, e.getMessage()));
            }
        }
        jdbc.update("""
                UPDATE contact_imports SET status = 'COMPLETED', created_rows = ?, updated_rows = ?, error_rows = ?, errors = ?::jsonb, completed_at = now()
                WHERE id = ?""", created, updated, failed, Json.write(errors), importId);
    }

    /** Removes contacts CREATED by this import that have no messages yet. Updated contacts are not reverted (documented). */
    @Transactional
    public int rollback(UUID orgId, UUID userId, UUID importId) {
        var imp = load(orgId, importId);
        if (!"COMPLETED".equals(imp.get("status"))) throw ApiException.invalidState("Only completed imports can be rolled back");
        int n = jdbc.update("""
                DELETE FROM contacts c WHERE c.organization_id = ? AND c.source = 'IMPORT' AND c.source_ref = ?
                  AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.contact_id = c.id)""", orgId, importId.toString());
        jdbc.update("UPDATE contact_imports SET status = 'ROLLED_BACK' WHERE id = ?", importId);
        audit.user(orgId, userId, "contacts.import_rolled_back", "import", importId, Map.of("deleted", n));
        return n;
    }

    public List<ImportView> list(UUID orgId) {
        return jdbc.query("""
                SELECT id, file_name, status, total_rows, created_rows, updated_rows, skipped_rows, error_rows, created_at, completed_at
                FROM contact_imports WHERE organization_id = ? ORDER BY created_at DESC LIMIT 50""",
                (rs, i) -> new ImportView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getInt(8), rs.getTimestamp(9).toInstant(), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()), orgId);
    }

    public String errors(UUID orgId, UUID importId) { return (String) jdbc.queryForMap("SELECT errors::text AS e FROM contact_imports WHERE id = ? AND organization_id = ?", importId, orgId).get("e"); }

    /** Retention: raw upload bytes are deleted after the configured period. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 17 3 * * *")
    public void purgeExpiredFiles() {
        jdbc.update("UPDATE contact_imports SET file_content = NULL WHERE file_content IS NOT NULL AND file_expires_at < now()");
    }

    private record Ctx(String country, ZoneId zone) {}

    private Ctx ctx(UUID orgId) {
        var m = jdbc.queryForMap("SELECT country_code, time_zone FROM organizations WHERE id = ?", orgId);
        return new Ctx((String) m.get("country_code"), ZoneId.of((String) m.get("time_zone")));
    }

    private Map<String, Object> load(UUID orgId, UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM contact_imports WHERE id = ? AND organization_id = ?", id, orgId);
        if (rows.isEmpty()) throw ApiException.notFound("Import");
        if (rows.getFirst().get("file_content") == null && !"COMPLETED".equals(rows.getFirst().get("status"))
                && !"ROLLED_BACK".equals(rows.getFirst().get("status"))) throw ApiException.invalidState("Import file expired; upload it again");
        return rows.getFirst();
    }

    static ContactInput toInput(List<String> headers, List<String> row, Map<String, String> mapping, Ctx ctx, List<String> extraTags) {
        Map<String, String> v = new HashMap<>();
        Map<String, Object> custom = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String target = mapping.getOrDefault(headers.get(i), "ignore");
            String value = i < row.size() ? row.get(i).trim() : "";
            if ("ignore".equals(target) || value.isEmpty()) continue;
            if (target.startsWith("custom.")) custom.put(target.substring(7), value);
            else v.put(target, value);
        }
        String email = v.get("email");
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException("invalid email: " + email);
        String phone = v.get("phone");
        if (email == null && phone == null) throw new IllegalArgumentException("missing email and phone");
        String country = v.get("country_code") == null ? null : v.get("country_code").toUpperCase(Locale.ROOT);
        if (country != null && !country.matches("[A-Z]{2}")) throw new IllegalArgumentException("invalid country code");
        Long ltv = null;
        if (v.get("lifetime_value") != null) {
            try { ltv = Math.round(Double.parseDouble(v.get("lifetime_value").replace(",", ".")) * 100); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("invalid lifetime value"); }
        }
        List<String> tags = new ArrayList<>(extraTags);
        if (v.get("tags") != null) for (String tg : v.get("tags").split("[,;|]")) if (!tg.isBlank()) tags.add(tg.trim());
        return new ContactInput(v.get("external_id"), v.get("name"), email, phone, v.get("locale"), country, v.get("time_zone"),
                date(v.get("birth_date"), ctx), inst(v.get("last_interaction_at"), ctx), inst(v.get("last_purchase_at"), ctx),
                inst(v.get("last_visit_at"), ctx), inst(v.get("next_appointment_at"), ctx), ltv, null, tags, custom.isEmpty() ? null : custom);
    }

    private static Instant inst(String raw, Ctx ctx) {
        if (raw == null) return null;
        return ImportParser.parseInstant(raw, ctx.country, ctx.zone).orElseThrow(() -> new IllegalArgumentException("invalid date: " + raw));
    }

    private static java.time.LocalDate date(String raw, Ctx ctx) {
        if (raw == null) return null;
        return ImportParser.parseDate(raw, ctx.country).orElseThrow(() -> new IllegalArgumentException("invalid date: " + raw));
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
