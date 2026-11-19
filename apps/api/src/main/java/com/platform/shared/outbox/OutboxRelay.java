package com.platform.shared.outbox;

import com.platform.shared.AppProperties;
import com.platform.shared.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Delivers outbox events to in-process handlers. Each (consumer, event) pair is applied at most
 * once thanks to inbox_deduplication written in the handler's transaction; the event is marked
 * published only when every subscribed consumer succeeded. Failures back off and retry; after
 * MAX_ATTEMPTS the event stays unpublished and is visible in the admin dead-letter view.
 */
@Component
public class OutboxRelay {
    public static final int MAX_ATTEMPTS = 12;
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final List<OutboxHandler> handlers;
    private final AppProperties props;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, List<OutboxHandler> handlers, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.handlers = handlers;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${app.workers.outbox-poll-ms:500}")
    public void poll() {
        if (!props.workers().enabled()) return;
        drain(200);
    }

    /** Processes up to {@code max} events; returns the number processed (used by tests too). */
    public int drain(int max) {
        int processed = 0;
        while (processed < max) {
            List<OutboxEvent> batch = tx.execute(s -> jdbc.query("""
                    UPDATE outbox_events SET available_at = now() + interval '60 seconds', attempts = attempts + 1
                    WHERE id IN (SELECT id FROM outbox_events WHERE published_at IS NULL AND available_at <= now() AND attempts < ?
                                 ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED)
                    RETURNING id, organization_id, aggregate_type, aggregate_id, event_type, payload::text AS payload
                    """, (rs, i) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                    rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
                    Json.read(rs.getString("payload"))), MAX_ATTEMPTS));
            if (batch == null || batch.isEmpty()) break;
            for (OutboxEvent e : batch) deliver(e);
            processed += batch.size();
        }
        return processed;
    }

    private void deliver(OutboxEvent event) {
        try {
            for (OutboxHandler h : handlers) {
                if (!h.eventTypes().contains(event.eventType())) continue;
                tx.executeWithoutResult(s -> {
                    int inserted = jdbc.update("INSERT INTO inbox_deduplication (consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                            h.consumer(), event.id().toString());
                    if (inserted == 1) h.handle(event);
                });
            }
            jdbc.update("UPDATE outbox_events SET published_at = now(), last_error = NULL WHERE id = ?", event.id());
        } catch (RuntimeException ex) {
            log.warn("Outbox event {} ({}) failed: {}", event.id(), event.eventType(), ex.toString());
            jdbc.update("UPDATE outbox_events SET available_at = ?, last_error = ? WHERE id = ?",
                    Timestamp.from(Instant.now().plusSeconds(30)), ex.toString().substring(0, Math.min(1000, ex.toString().length())), event.id());
        }
    }
}
