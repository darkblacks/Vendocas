package com.platform.automation.engine;

import com.platform.automation.model.AutomationDefinition;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates automation instances. The unique (automation_id, idempotency_key) constraint guarantees
 * that a duplicated/replayed trigger never creates a second instance.
 */
@Component
public class InstanceFactory {
    private final JdbcTemplate jdbc;

    public InstanceFactory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean create(UUID orgId, UUID automationId, UUID versionId, AutomationDefinition def, UUID contactId, String sourceType,
                          UUID sourceEventId, String idempotencyKey, Map<String, ?> context, Instant startAt) {
        return jdbc.update("""
                INSERT INTO automation_instances (id, organization_id, automation_id, version_id, contact_id, source_event_type, source_event_id,
                    idempotency_key, state, current_step_id, next_run_at, context)
                VALUES (?,?,?,?,?,?,?,?, 'READY', ?, ?, ?::jsonb) ON CONFLICT (automation_id, idempotency_key) DO NOTHING""",
                Ids.newId(), orgId, automationId, versionId, contactId, sourceType, sourceEventId, idempotencyKey,
                def.steps().getFirst().id(), Timestamp.from(startAt), Json.write(context)) == 1;
    }

    /** SQL fragment restricting contacts (alias c) to the trigger audience. */
    public static String audienceSql(AutomationDefinition.Audience a, MapSqlParameterSource p) {
        StringBuilder sb = new StringBuilder(" AND c.status = 'ACTIVE'");
        if (a == null) return sb.toString();
        if (a.tagsAny() != null && !a.tagsAny().isEmpty()) {
            sb.append(" AND EXISTS (SELECT 1 FROM contact_tags ct JOIN tags t ON t.id = ct.tag_id WHERE ct.contact_id = c.id AND t.name IN (:tagsAny))");
            p.addValue("tagsAny", a.tagsAny());
        }
        if (a.tagsNone() != null && !a.tagsNone().isEmpty()) {
            sb.append(" AND NOT EXISTS (SELECT 1 FROM contact_tags ct JOIN tags t ON t.id = ct.tag_id WHERE ct.contact_id = c.id AND t.name IN (:tagsNone))");
            p.addValue("tagsNone", a.tagsNone());
        }
        if (a.countries() != null && !a.countries().isEmpty()) {
            sb.append(" AND c.country_code IN (:countries)");
            p.addValue("countries", a.countries());
        }
        return sb.toString();
    }

    public static boolean matchesAudience(AutomationDefinition.Audience a, List<String> tags, String country) {
        if (a == null) return true;
        if (a.tagsAny() != null && !a.tagsAny().isEmpty() && a.tagsAny().stream().noneMatch(tags::contains)) return false;
        if (a.tagsNone() != null && a.tagsNone().stream().anyMatch(tags::contains)) return false;
        return a.countries() == null || a.countries().isEmpty() || a.countries().contains(country);
    }
}
