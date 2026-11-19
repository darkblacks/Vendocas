package com.platform.shared.jobs;

import com.platform.shared.AppProperties;
import com.platform.shared.Json;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Durable job runner on PostgreSQL. Claims due jobs with FOR UPDATE SKIP LOCKED and a lease, so
 * several API instances can run concurrently and a crashed instance's jobs are reclaimed when
 * the lease expires. Each job runs and is marked DONE in a single transaction.
 */
@Component
public class JobWorker {
    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Map<String, JobHandler> handlers;
    private final AppProperties props;
    private final MeterRegistry meters;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();

    public JobWorker(JdbcTemplate jdbc, TransactionTemplate tx, List<JobHandler> handlers, AppProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.props = props;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${app.workers.job-poll-ms:1000}")
    public void poll() {
        if (!props.workers().enabled()) return;
        runOnce();
    }

    public int runOnce() {
        List<Job> jobs = claim();
        for (Job job : jobs) execute(job);
        return jobs.size();
    }

    private List<Job> claim() {
        long leaseSeconds = props.workers().leaseDuration().toSeconds();
        return jdbc.query("""
                UPDATE scheduled_jobs SET status='RUNNING', lease_until=now() + make_interval(secs => ?), leased_by=?,
                       attempts=attempts+1, updated_at=now()
                WHERE id IN (SELECT id FROM scheduled_jobs
                             WHERE (status='PENDING' AND run_at <= now()) OR (status='RUNNING' AND lease_until < now())
                             ORDER BY run_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id, job_type, organization_id, payload::text AS payload, attempts, max_attempts
                """, (rs, i) -> new Job(rs.getObject("id", UUID.class), rs.getString("job_type"),
                        rs.getObject("organization_id", UUID.class), Json.read(rs.getString("payload")),
                        rs.getInt("attempts"), rs.getInt("max_attempts")),
                leaseSeconds, workerId, props.workers().batchSize());
    }

    private void execute(Job job) {
        JobHandler handler = handlers.get(job.type());
        try {
            if (handler == null) throw new IllegalStateException("No handler for job type " + job.type());
            tx.executeWithoutResult(s -> {
                handler.handle(job);
                jdbc.update("UPDATE scheduled_jobs SET status='DONE', lease_until=NULL, updated_at=now() WHERE id=?", job.id());
            });
            meters.counter("jobs.completed", "type", job.type()).increment();
        } catch (RuntimeException e) {
            boolean dead = job.attempts() >= job.maxAttempts();
            long backoff = Math.min(3600, (long) Math.pow(2, job.attempts()) * 15);
            log.warn("Job {} ({}) failed attempt {}/{}: {}", job.id(), job.type(), job.attempts(), job.maxAttempts(), e.toString());
            jdbc.update("UPDATE scheduled_jobs SET status=?, run_at=?, lease_until=NULL, last_error=?, updated_at=now() WHERE id=?",
                    dead ? "DEAD" : "PENDING", Timestamp.from(Instant.now().plusSeconds(backoff)),
                    truncate(e.toString(), 2000), job.id());
            meters.counter(dead ? "jobs.dead" : "jobs.retried", "type", job.type()).increment();
        }
    }

    private static String truncate(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
