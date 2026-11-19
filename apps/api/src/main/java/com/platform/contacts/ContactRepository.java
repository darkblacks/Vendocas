package com.platform.contacts;

import com.platform.shared.Json;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** JDBC repository: every query is scoped by organization_id. */
@Repository
public class ContactRepository {
    private static final String SELECT = """
            SELECT c.*, COALESCE((SELECT array_agg(t.name ORDER BY t.name) FROM contact_tags ct JOIN tags t ON t.id = ct.tag_id
                                  WHERE ct.contact_id = c.id), '{}') AS tag_names
            FROM contacts c""";
    private final NamedParameterJdbcTemplate jdbc;

    public ContactRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @SuppressWarnings("unchecked")
    static final RowMapper<Contact> MAPPER = (rs, i) -> {
        Array tags = rs.getArray("tag_names");
        return new Contact(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("external_id"),
                rs.getString("name"), rs.getString("email"), rs.getString("phone_e164"), rs.getString("locale"), rs.getString("country_code"),
                rs.getString("time_zone"), rs.getObject("birth_date", java.time.LocalDate.class), inst(rs.getTimestamp("last_interaction_at")),
                inst(rs.getTimestamp("last_purchase_at")), inst(rs.getTimestamp("last_visit_at")), inst(rs.getTimestamp("next_appointment_at")),
                (Long) rs.getObject("lifetime_value_minor"), rs.getString("lifetime_value_currency"), rs.getString("status"),
                rs.getString("source"), Json.read(rs.getString("custom_fields"), Map.class),
                tags == null ? List.of() : List.of((String[]) tags.getArray()), inst(rs.getTimestamp("created_at")), inst(rs.getTimestamp("updated_at")));
    };

    public Optional<Contact> find(UUID orgId, UUID id) {
        return jdbc.query(SELECT + " WHERE c.organization_id = :org AND c.id = :id", Map.of("org", orgId, "id", id), MAPPER).stream().findFirst();
    }

    public Optional<Contact> findByEmail(UUID orgId, String email) {
        return jdbc.query(SELECT + " WHERE c.organization_id = :org AND c.email = :v AND c.status <> 'ERASED'", Map.of("org", orgId, "v", email), MAPPER).stream().findFirst();
    }

    public Optional<Contact> findByPhone(UUID orgId, String phone) {
        return jdbc.query(SELECT + " WHERE c.organization_id = :org AND c.phone_e164 = :v AND c.status <> 'ERASED'", Map.of("org", orgId, "v", phone), MAPPER).stream().findFirst();
    }

    public Optional<Contact> findByExternalId(UUID orgId, String ext) {
        return jdbc.query(SELECT + " WHERE c.organization_id = :org AND c.external_id = :v", Map.of("org", orgId, "v", ext), MAPPER).stream().findFirst();
    }

    public record Filter(String q, String tag, String status, String country, Instant lastPurchaseBefore, Instant lastInteractionBefore,
                         String consentChannel, String consentPurpose) {}

    public List<Contact> search(UUID orgId, Filter f, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("org", orgId).addValue("limit", size).addValue("offset", page * size);
        return jdbc.query(SELECT + where(f, p) + " ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset", p, MAPPER);
    }

    public long count(UUID orgId, Filter f) {
        MapSqlParameterSource p = new MapSqlParameterSource("org", orgId);
        Long n = jdbc.queryForObject("SELECT count(*) FROM contacts c" + where(f, p), p, Long.class);
        return n == null ? 0 : n;
    }

    static String where(Filter f, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" WHERE c.organization_id = :org");
        if (f == null) return w.append(" AND c.status <> 'ERASED'").toString();
        if (f.status() != null) { w.append(" AND c.status = :status"); p.addValue("status", f.status()); }
        else w.append(" AND c.status <> 'ERASED'");
        if (f.q() != null && !f.q().isBlank()) {
            w.append(" AND (lower(c.name) LIKE :q OR c.email::text LIKE :q OR c.phone_e164 LIKE :q OR c.external_id = :qexact)");
            p.addValue("q", "%" + f.q().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%").addValue("qexact", f.q());
        }
        if (f.tag() != null) {
            w.append(" AND EXISTS (SELECT 1 FROM contact_tags ct JOIN tags t ON t.id = ct.tag_id WHERE ct.contact_id = c.id AND t.name = :tag)");
            p.addValue("tag", f.tag());
        }
        if (f.country() != null) { w.append(" AND c.country_code = :country"); p.addValue("country", f.country()); }
        if (f.lastPurchaseBefore() != null) { w.append(" AND c.last_purchase_at < :lpb"); p.addValue("lpb", Timestamp.from(f.lastPurchaseBefore())); }
        if (f.lastInteractionBefore() != null) { w.append(" AND COALESCE(c.last_interaction_at, c.created_at) < :lib"); p.addValue("lib", Timestamp.from(f.lastInteractionBefore())); }
        if (f.consentChannel() != null && f.consentPurpose() != null) {
            w.append(" AND EXISTS (SELECT 1 FROM contact_consents cc WHERE cc.contact_id = c.id AND cc.channel = :cch AND cc.purpose = :cpu AND cc.status = 'GRANTED')");
            p.addValue("cch", f.consentChannel()).addValue("cpu", f.consentPurpose());
        }
        return w.toString();
    }

    public void insert(Contact c) {
        jdbc.update("""
                INSERT INTO contacts (id, organization_id, external_id, name, email, phone_e164, locale, country_code, time_zone, birth_date,
                    last_interaction_at, last_purchase_at, last_visit_at, next_appointment_at, lifetime_value_minor, lifetime_value_currency,
                    status, source, custom_fields)
                VALUES (:id, :org, :ext, :name, :email, :phone, :locale, :country, :tz, :birth, :li, :lp, :lv, :na, :ltv, :ltvc, :status, :source, CAST(:cf AS jsonb))""",
                params(c));
    }

    public int update(Contact c) {
        return jdbc.update("""
                UPDATE contacts SET external_id=:ext, name=:name, email=:email, phone_e164=:phone, locale=:locale, country_code=:country,
                    time_zone=:tz, birth_date=:birth, last_interaction_at=:li, last_purchase_at=:lp, last_visit_at=:lv, next_appointment_at=:na,
                    lifetime_value_minor=:ltv, lifetime_value_currency=:ltvc, status=:status, custom_fields=CAST(:cf AS jsonb), updated_at=now(),
                    version = version + 1
                WHERE organization_id=:org AND id=:id""", params(c));
    }

    private static MapSqlParameterSource params(Contact c) {
        return new MapSqlParameterSource().addValue("id", c.id()).addValue("org", c.organizationId()).addValue("ext", c.externalId())
                .addValue("name", c.name()).addValue("email", c.email()).addValue("phone", c.phoneE164()).addValue("locale", c.locale())
                .addValue("country", c.countryCode()).addValue("tz", c.timeZone()).addValue("birth", c.birthDate())
                .addValue("li", ts(c.lastInteractionAt())).addValue("lp", ts(c.lastPurchaseAt())).addValue("lv", ts(c.lastVisitAt()))
                .addValue("na", ts(c.nextAppointmentAt())).addValue("ltv", c.lifetimeValueMinor()).addValue("ltvc", c.lifetimeValueCurrency())
                .addValue("status", c.status()).addValue("source", c.source())
                .addValue("cf", Json.write(c.customFields() == null ? Map.of() : c.customFields()));
    }

    public void setTags(UUID orgId, UUID contactId, Collection<String> tagNames) {
        jdbc.update("DELETE FROM contact_tags WHERE contact_id = :c AND contact_id IN (SELECT id FROM contacts WHERE organization_id = :org)",
                Map.of("c", contactId, "org", orgId));
        for (String name : new LinkedHashSet<>(tagNames)) {
            String clean = name.trim();
            if (clean.isEmpty() || clean.length() > 80) continue;
            jdbc.update("INSERT INTO tags (id, organization_id, name) VALUES (:id, :org, :n) ON CONFLICT (organization_id, name) DO NOTHING",
                    Map.of("id", com.platform.shared.Ids.newId(), "org", orgId, "n", clean));
            jdbc.update("""
                    INSERT INTO contact_tags (contact_id, tag_id) SELECT :c, id FROM tags WHERE organization_id = :org AND name = :n
                    ON CONFLICT DO NOTHING""", Map.of("c", contactId, "org", orgId, "n", clean));
        }
    }

    private static Instant inst(Timestamp t) { return t == null ? null : t.toInstant(); }

    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
}
