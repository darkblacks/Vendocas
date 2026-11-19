package com.platform.automation.engine;

import com.platform.automation.model.AutomationDefinition;
import com.platform.automation.model.TimeRules;
import com.platform.automation.model.TriggerType;
import com.platform.shared.AppProperties;
import com.platform.shared.Json;
import com.platform.shared.jobs.Job;
import com.platform.shared.jobs.JobHandler;
import com.platform.shared.jobs.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.*;
import java.util.Map;
import java.util.UUID;

/**
 * Time-based triggers. A scheduler tick makes sure each ACTIVE sweep/recurring automation has its
 * next durable job; jobs are deduplicated by key (automation + period bucket / occurrence), so many
 * API instances can tick concurrently and a restart never loses or duplicates a run.
 * Instances are created in bulk with INSERT … SELECT … ON CONFLICT DO NOTHING.
 */
@Component
public class SweepJobs implements JobHandler {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final JobService jobs;
    private final VersionCache versions;
    private final AppProperties props;
    private final Clock clock;

    public SweepJobs(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, JobService jobs, VersionCache versions, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.named = named;
        this.jobs = jobs;
        this.versions = versions;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.sweep-tick-ms:60000}", initialDelay = 15000)
    public void tick() {
        if (props.workers().enabled()) ensureJobs();
    }

    public void ensureJobs() {
        Instant now = clock.instant();
        var rows = jdbc.queryForList("""
                SELECT d.id, d.organization_id, d.active_version_id, t.trigger_type, o.time_zone FROM automation_definitions d
                JOIN automation_triggers t ON t.version_id = d.active_version_id JOIN organizations o ON o.id = d.organization_id
                WHERE d.status = 'ACTIVE' AND t.trigger_type IN ('CONTACT_INACTIVE','APPOINTMENT_UPCOMING','BIRTHDAY','DATE_FIELD','RECURRING')""");
        for (var r : rows) {
            UUID id = (UUID) r.get("id");
            UUID org = (UUID) r.get("organization_id");
            UUID version = (UUID) r.get("active_version_id");
            ZoneId zone = ZoneId.of((String) r.get("time_zone"));
            TriggerType tt = TriggerType.valueOf((String) r.get("trigger_type"));
            Map<String, String> payload = Map.of("automationId", id.toString(), "versionId", version.toString());
            switch (tt) {
                case RECURRING -> {
                    AutomationDefinition def = versions.get(version);
                    Instant next = TimeRules.nextOccurrence(now.minusSeconds(1), def.trigger().schedule(), zone);
                    jobs.schedule("automation.sweep", "recur:" + version + ":" + next.getEpochSecond(), org,
                            Map.of("automationId", id.toString(), "versionId", version.toString(), "occurrence", next.toString()), next);
                }
                case APPOINTMENT_UPCOMING -> jobs.schedule("automation.sweep", "appt:" + version + ":" + now.getEpochSecond() / 600, org, payload, now);
                default -> jobs.schedule("automation.sweep", "daily:" + version + ":" + LocalDate.now(zone), org, payload, now);
            }
        }
    }

    @Override public String type() { return "automation.sweep"; }

    @Override
    public void handle(Job job) {
        UUID automationId = UUID.fromString(job.payload().path("automationId").asString());
        UUID versionId = UUID.fromString(job.payload().path("versionId").asString());
        var active = jdbc.queryForList("SELECT o.time_zone FROM automation_definitions d JOIN organizations o ON o.id = d.organization_id WHERE d.id = ? AND d.status = 'ACTIVE' AND d.active_version_id = ?",
                String.class, automationId, versionId);
        if (active.isEmpty()) return; // paused / re-published meanwhile
        ZoneId zone = ZoneId.of(active.getFirst());
        AutomationDefinition def = versions.get(versionId);
        UUID org = job.organizationId();
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("org", org).addValue("aid", automationId).addValue("vid", versionId)
                .addValue("first", def.steps().getFirst().id()).addValue("now", Timestamp.from(now));
        String audience = InstanceFactory.audienceSql(def.trigger().audience(), p);
        String insert = """
                INSERT INTO automation_instances (id, organization_id, automation_id, version_id, contact_id, source_event_type, idempotency_key,
                    state, current_step_id, next_run_at, context)
                SELECT gen_random_uuid(), :org, :aid, :vid, c.id, '%s', %s, 'READY', :first, :now, %s
                FROM contacts c WHERE c.organization_id = :org %s %s
                ON CONFLICT (automation_id, idempotency_key) DO NOTHING""";
        switch (def.trigger().type()) {
            case CONTACT_INACTIVE -> {
                String basis = switch (def.trigger().basis() == null ? "LAST_INTERACTION" : def.trigger().basis()) {
                    case "LAST_PURCHASE" -> "c.last_purchase_at";
                    case "LAST_VISIT" -> "c.last_visit_at";
                    default -> "COALESCE(c.last_interaction_at, c.last_purchase_at, c.created_at)";
                };
                p.addValue("threshold", Timestamp.from(TimeRules.subtract(now, def.trigger().period(), zone)));
                // One instance per inactivity episode: the key contains the anchor date, so a new purchase starts a new episode.
                named.update(insert.formatted("CONTACT_INACTIVE", "'INACTIVE:' || c.id || ':' || floor(extract(epoch FROM " + basis + "))::bigint",
                        "jsonb_build_object('triggerAt', :now::text, 'anchor', " + basis + "::text)", "AND " + basis + " IS NOT NULL AND " + basis + " <= :threshold", audience), p);
            }
            case BIRTHDAY -> {
                LocalDate today = LocalDate.now(zone);
                p.addValue("m", today.getMonthValue()).addValue("d", today.getDayOfMonth()).addValue("y", today.getYear());
                // Feb 29 birthdays are celebrated on Feb 28 in non-leap years.
                String feb29 = (!today.isLeapYear() && today.getMonthValue() == 2 && today.getDayOfMonth() == 28)
                        ? " OR (extract(month FROM c.birth_date) = 2 AND extract(day FROM c.birth_date) = 29)" : "";
                named.update(insert.formatted("BIRTHDAY", "'BDAY:' || c.id || ':' || :y", "jsonb_build_object('triggerAt', :now::text)",
                        "AND c.birth_date IS NOT NULL AND ((extract(month FROM c.birth_date) = :m AND extract(day FROM c.birth_date) = :d)" + feb29 + ")", audience), p);
            }
            case DATE_FIELD -> {
                p.addValue("key", def.trigger().dateField()).addValue("today", LocalDate.now(zone).toString());
                named.update(insert.formatted("DATE_FIELD", "'DATE:' || c.id || ':' || :today", "jsonb_build_object('triggerAt', :now::text)",
                        "AND left(c.custom_fields ->> :key, 10) = :today", audience), p);
            }
            case APPOINTMENT_UPCOMING -> {
                p.addValue("until", Timestamp.from(now.plus(Duration.ofMinutes(def.trigger().leadMinutes()))));
                named.update("""
                        INSERT INTO automation_instances (id, organization_id, automation_id, version_id, contact_id, source_event_type, idempotency_key,
                            state, current_step_id, next_run_at, context)
                        SELECT gen_random_uuid(), :org, :aid, :vid, c.id, 'APPOINTMENT_UPCOMING', 'APPT:' || a.id || ':' || floor(extract(epoch FROM a.starts_at))::bigint,
                               'READY', :first, :now, jsonb_build_object('triggerAt', :now::text, 'appointmentId', a.id::text, 'appointment.starts_at', a.starts_at::text)
                        FROM appointments a JOIN contacts c ON c.id = a.contact_id
                        WHERE a.organization_id = :org AND a.status IN ('SCHEDULED','CONFIRMED') AND a.starts_at > :now AND a.starts_at <= :until""" + audience + """
                         ON CONFLICT (automation_id, idempotency_key) DO NOTHING""", p);
            }
            case RECURRING -> {
                Instant occurrence = Instant.parse(job.payload().path("occurrence").asString());
                p.addValue("occ", occurrence.toString());
                named.update(insert.formatted("RECURRING", "'RECUR:' || c.id || ':' || :occ", "jsonb_build_object('triggerAt', :now::text, 'occurrence', :occ)", "", audience), p);
                Instant next = TimeRules.nextOccurrence(occurrence, def.trigger().schedule(), zone);
                jobs.schedule("automation.sweep", "recur:" + versionId + ":" + next.getEpochSecond(), org,
                        Map.of("automationId", automationId.toString(), "versionId", versionId.toString(), "occurrence", next.toString()), next);
            }
            default -> { }
        }
    }

    static String json(Object o) { return Json.write(o); }
}
