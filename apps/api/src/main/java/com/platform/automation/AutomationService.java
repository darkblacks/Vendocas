package com.platform.automation;

import com.platform.audit.AuditService;
import com.platform.automation.engine.SweepJobs;
import com.platform.automation.engine.VersionCache;
import com.platform.automation.model.*;
import com.platform.billing.EntitlementService;
import com.platform.channels.ChannelService;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.*;

@Service
public class AutomationService {
    private final JdbcTemplate jdbc;
    private final EntitlementService entitlements;
    private final ChannelService channels;
    private final AuditService audit;
    private final SweepJobs sweeps;

    public AutomationService(JdbcTemplate jdbc, EntitlementService entitlements, ChannelService channels, AuditService audit, SweepJobs sweeps) {
        this.jdbc = jdbc;
        this.entitlements = entitlements;
        this.channels = channels;
        this.audit = audit;
        this.sweeps = sweeps;
    }

    public record Summary(UUID id, String name, String description, String status, String editorMode, String triggerType, Integer activeVersion,
                          Integer draftVersion, long running, long completed, long blocked, Instant updatedAt) {}

    public record Detail(Summary summary, AutomationDefinition draft, AutomationDefinition active, List<String> validationErrors) {}

    public record VersionView(UUID id, int versionNo, String triggerType, Instant publishedAt, String migrationPolicy, Instant createdAt) {}

    public List<Summary> list(UUID org) {
        return jdbc.query("""
                SELECT d.id, d.name, d.description, d.status, d.editor_mode, d.trigger_type,
                  (SELECT version_no FROM automation_versions WHERE id = d.active_version_id),
                  (SELECT version_no FROM automation_versions WHERE id = d.draft_version_id AND published_at IS NULL),
                  (SELECT count(*) FROM automation_instances i WHERE i.automation_id = d.id AND i.state IN ('PENDING','WAITING','READY','PROCESSING')),
                  (SELECT count(*) FROM automation_instances i WHERE i.automation_id = d.id AND i.state = 'COMPLETED'),
                  (SELECT count(*) FROM automation_instances i WHERE i.automation_id = d.id AND i.state = 'SKIPPED'),
                  d.updated_at
                FROM automation_definitions d WHERE d.organization_id = ? AND d.status <> 'ARCHIVED' ORDER BY d.updated_at DESC""",
                (rs, i) -> new Summary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        (Integer) rs.getObject(7), (Integer) rs.getObject(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getTimestamp(12).toInstant()), org);
    }

    public Detail get(UUID org, UUID id) {
        Summary s = list(org).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Automation"));
        var row = jdbc.queryForMap("SELECT draft_version_id, active_version_id FROM automation_definitions WHERE id = ?", id);
        AutomationDefinition draft = row.get("draft_version_id") == null ? null : load((UUID) row.get("draft_version_id"));
        AutomationDefinition active = row.get("active_version_id") == null ? null : load((UUID) row.get("active_version_id"));
        return new Detail(s, draft, active, draft == null ? List.of() : DefinitionValidator.validate(draft));
    }

    @Transactional
    public UUID create(UUID org, UUID userId, String name, String description, String mode, AutomationDefinition def) {
        if (def == null || def.trigger() == null || def.trigger().type() == null) throw ApiException.badRequest("Choose a trigger");
        UUID id = Ids.newId();
        UUID versionId = Ids.newId();
        jdbc.update("""
                INSERT INTO automation_definitions (id, organization_id, name, description, status, editor_mode, draft_version_id, created_by)
                VALUES (?,?,?,?, 'DRAFT', ?, ?, ?)""", id, org, name, description, mode == null ? "SIMPLE" : mode, versionId, userId);
        jdbc.update("""
                INSERT INTO automation_versions (id, automation_id, organization_id, version_no, trigger_type, definition, created_by)
                VALUES (?,?,?,1,?,?::jsonb,?)""", versionId, id, org, def.trigger().type().name(), Json.write(def), userId);
        audit.user(org, userId, "automation.created", "automation", id, Map.of("name", name));
        return id;
    }

    /** Saves the draft. If the current draft was already published, a new draft version is created (published versions are immutable). */
    @Transactional
    public void saveDraft(UUID org, UUID userId, UUID id, String name, String description, String mode, AutomationDefinition def) {
        var row = definition(org, id);
        if ("ARCHIVED".equals(row.get("status"))) throw ApiException.invalidState("Archived automations cannot be edited");
        UUID draftId = (UUID) row.get("draft_version_id");
        boolean draftEditable = draftId != null && jdbc.queryForObject("SELECT published_at IS NULL FROM automation_versions WHERE id = ?", Boolean.class, draftId);
        if (draftEditable) {
            jdbc.update("UPDATE automation_versions SET definition = ?::jsonb, trigger_type = ? WHERE id = ?", Json.write(def), def.trigger().type().name(), draftId);
        } else {
            UUID nv = Ids.newId();
            int no = jdbc.queryForObject("SELECT COALESCE(max(version_no),0)+1 FROM automation_versions WHERE automation_id = ?", Integer.class, id);
            jdbc.update("""
                    INSERT INTO automation_versions (id, automation_id, organization_id, version_no, trigger_type, definition, created_by)
                    VALUES (?,?,?,?,?,?::jsonb,?)""", nv, id, org, no, def.trigger().type().name(), Json.write(def), userId);
            jdbc.update("UPDATE automation_definitions SET draft_version_id = ? WHERE id = ?", nv, id);
        }
        jdbc.update("UPDATE automation_definitions SET name = COALESCE(?, name), description = ?, editor_mode = COALESCE(?, editor_mode), updated_at = now() WHERE id = ?",
                name, description, mode, id);
    }

    /**
     * Publishes the draft and activates it. Activation is refused unless the automation is eligible:
     * valid graph, active subscription with AUTOMATIONS, every channel used is in the plan and connected.
     */
    @Transactional
    public void activate(UUID org, UUID userId, UUID id, String migrationPolicy) {
        var row = definition(org, id);
        UUID draftId = (UUID) row.get("draft_version_id");
        UUID activeId = (UUID) row.get("active_version_id");
        boolean draftPublished = draftId == null || jdbc.queryForObject("SELECT published_at IS NOT NULL FROM automation_versions WHERE id = ?", Boolean.class, draftId);
        UUID versionToActivate = draftPublished ? activeId : draftId;
        if (versionToActivate == null) throw ApiException.invalidState("Nothing to activate");
        AutomationDefinition def = load(versionToActivate);
        DefinitionValidator.require(def);
        eligibility(org, def);
        if (!draftPublished) {
            jdbc.update("UPDATE automation_versions SET published_at = now(), migration_policy = ? WHERE id = ?",
                    "CANCEL_RUNNING".equals(migrationPolicy) ? "CANCEL_RUNNING" : "KEEP_ON_OLD", versionToActivate);
            projectTrigger(org, id, versionToActivate, def);
            if (activeId != null && "CANCEL_RUNNING".equals(migrationPolicy)) {
                jdbc.update("""
                        UPDATE automation_instances SET state = 'CANCELED', result = 'Replaced by a new version', completed_at = now()
                        WHERE automation_id = ? AND version_id <> ? AND state IN ('PENDING','WAITING','READY')""", id, versionToActivate);
            }
        }
        jdbc.update("UPDATE automation_definitions SET status = 'ACTIVE', active_version_id = ?, trigger_type = ?, updated_at = now() WHERE id = ?",
                versionToActivate, def.trigger().type().name(), id);
        audit.user(org, userId, "automation.activated", "automation", id, Map.of("versionId", versionToActivate.toString()));
        sweeps.ensureJobs();
    }

    @Transactional
    public void setStatus(UUID org, UUID userId, UUID id, String status) {
        var row = definition(org, id);
        if ("ACTIVE".equals(status)) { activate(org, userId, id, null); return; }
        if (!Set.of("PAUSED", "ARCHIVED").contains(status)) throw ApiException.badRequest("Invalid status");
        if ("ARCHIVED".equals(status)) {
            jdbc.update("UPDATE automation_instances SET state = 'CANCELED', result = 'Automation archived', completed_at = now() WHERE automation_id = ? AND state IN ('PENDING','WAITING','READY')", id);
        }
        jdbc.update("UPDATE automation_definitions SET status = ?, updated_at = now() WHERE id = ?", status, id);
        audit.user(org, userId, "automation." + status.toLowerCase(Locale.ROOT), "automation", id, Map.of("from", String.valueOf(row.get("status"))));
    }

    @Transactional
    public UUID duplicate(UUID org, UUID userId, UUID id) {
        var row = definition(org, id);
        UUID src = row.get("draft_version_id") != null ? (UUID) row.get("draft_version_id") : (UUID) row.get("active_version_id");
        return create(org, userId, row.get("name") + " (copy)", (String) row.get("description"), (String) row.get("editor_mode"), load(src));
    }

    public List<VersionView> versions(UUID org, UUID id) {
        definition(org, id);
        return jdbc.query("SELECT id, version_no, trigger_type, published_at, migration_policy, created_at FROM automation_versions WHERE automation_id = ? ORDER BY version_no DESC",
                (rs, i) -> new VersionView(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getTimestamp(6).toInstant()), id);
    }

    public void eligibility(UUID org, AutomationDefinition def) {
        var e = entitlements.of(org);
        if (!e.serviceActive()) throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE, "An active subscription is required to activate automations");
        if (!e.features().contains(EntitlementService.AUTOMATIONS)) throw new ApiException(ErrorCode.PLAN_FEATURE_REQUIRED, "Your plan does not include automations");
        List<String> problems = new ArrayList<>();
        for (var s : def.steps()) {
            if (s.type() != StepType.SEND) continue;
            if (!e.features().contains(EntitlementService.featureForChannel(s.channel()))) problems.add(s.channel() + " is not included in your plan");
            else if (channels.sendable(org, s.channel()).isEmpty()) problems.add("Connect " + s.channel() + " before activating");
            if (s.templateId() != null && "WHATSAPP".equals(s.channel())) {
                String st = jdbc.queryForList("SELECT status FROM message_templates WHERE id = ? AND organization_id = ?", String.class, s.templateId(), org)
                        .stream().findFirst().orElse("MISSING");
                if (!"APPROVED".equals(st)) problems.add("WhatsApp template in step " + s.id() + " is " + st);
            }
        }
        if (!problems.isEmpty()) throw new ApiException(ErrorCode.INVALID_STATE, "This automation is not eligible for activation", Map.of("errors", problems));
    }

    private void projectTrigger(UUID org, UUID automationId, UUID versionId, AutomationDefinition def) {
        var t = def.trigger();
        Integer dow = null;
        if (t.schedule() != null && t.schedule().daysOfWeek() != null) {
            int mask = 0;
            for (int d : t.schedule().daysOfWeek()) mask |= 1 << (d - 1);
            dow = mask;
        }
        jdbc.update("""
                INSERT INTO automation_triggers (version_id, organization_id, automation_id, trigger_type, event_type, period_amount, period_unit, recurrence, local_time, days_of_week)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (version_id) DO NOTHING""", versionId, org, automationId, t.type().name(), t.eventType(),
                t.period() == null ? null : t.period().amount(), t.period() == null ? null : t.period().unit().name(),
                t.schedule() == null ? null : t.schedule().recurrence(), t.schedule() == null ? null : LocalTime.parse(t.schedule().time()), dow);
    }

    Map<String, Object> definition(UUID org, UUID id) {
        var rows = jdbc.queryForList("SELECT * FROM automation_definitions WHERE id = ? AND organization_id = ? FOR UPDATE", id, org);
        if (rows.isEmpty()) throw ApiException.notFound("Automation");
        return rows.getFirst();
    }

    AutomationDefinition load(UUID versionId) {
        return VersionCache.parse(jdbc.queryForObject("SELECT definition::text FROM automation_versions WHERE id = ?", String.class, versionId));
    }

}
