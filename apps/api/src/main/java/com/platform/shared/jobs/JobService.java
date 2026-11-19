package com.platform.shared.jobs;

import com.platform.shared.Ids;
import com.platform.shared.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class JobService {
    private final JdbcTemplate jdbc;

    public JobService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Idempotent: a job with the same dedupe key is created at most once. Returns true if created. */
    public boolean schedule(String type, String dedupeKey, UUID organizationId, Map<String, ?> payload, Instant runAt) {
        return jdbc.update("""
                INSERT INTO scheduled_jobs (id, organization_id, job_type, dedupe_key, payload, status, run_at)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', ?)
                ON CONFLICT (dedupe_key) DO NOTHING
                """, Ids.newId(), organizationId, type, dedupeKey, Json.write(payload), Timestamp.from(runAt)) == 1;
    }

    public int retryDead(UUID jobId) {
        return jdbc.update("UPDATE scheduled_jobs SET status='PENDING', attempts=0, run_at=now(), last_error=NULL, updated_at=now() WHERE id=? AND status IN ('DEAD','FAILED')", jobId);
    }
}
