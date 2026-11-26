package com.platform.crm;

import com.platform.shared.AppProperties;
import com.platform.shared.jobs.Job;
import com.platform.shared.jobs.JobHandler;
import com.platform.shared.jobs.JobService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/**
 * Daily CRM recomputation: one durable job per organization per local day (deduplicated by key, so several API instances
 * can tick and a restart neither loses nor repeats a day). Runs after 03:00 in the store's time zone.
 */
@Component
public class CrmJobs implements JobHandler {
    static final String TYPE = "crm.recompute";
    private final JdbcTemplate jdbc;
    private final JobService jobs;
    private final CrmService crm;
    private final AppProperties props;
    private final Clock clock;

    public CrmJobs(JdbcTemplate jdbc, JobService jobs, CrmService crm, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.crm = crm;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.crm-tick-ms:900000}", initialDelay = 60000)
    public void tick() {
        if (props.workers().enabled()) ensureJobs();
    }

    public int ensureJobs() {
        int scheduled = 0;
        var now = clock.instant();
        for (var r : jdbc.queryForList("SELECT id, time_zone FROM organizations WHERE status = 'ACTIVE'")) {
            UUID org = (UUID) r.get("id");
            ZoneId zone = ZoneId.of((String) r.get("time_zone"));
            var local = now.atZone(zone);
            if (local.getHour() < 3) continue;
            LocalDate day = local.toLocalDate();
            if (jobs.schedule(TYPE, "crm:" + org + ":" + day, org, Map.of("day", day.toString()), now)) scheduled++;
        }
        return scheduled;
    }

    @Override public String type() { return TYPE; }

    @Override
    public void handle(Job job) { crm.recompute(job.organizationId()); }
}
