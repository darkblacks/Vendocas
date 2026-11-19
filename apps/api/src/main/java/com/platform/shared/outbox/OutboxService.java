package com.platform.shared.outbox;

import com.platform.shared.Ids;
import com.platform.shared.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** Transactional outbox: events are written in the same transaction as the state change. */
@Service
public class OutboxService {
    private final JdbcTemplate jdbc;

    public OutboxService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(UUID organizationId, String aggregateType, UUID aggregateId, String eventType, Map<String, ?> payload) {
        UUID id = Ids.newId();
        jdbc.update("INSERT INTO outbox_events (id, organization_id, aggregate_type, aggregate_id, event_type, payload) VALUES (?,?,?,?,?,?::jsonb)",
                id, organizationId, aggregateType, aggregateId, eventType, Json.write(payload));
        return id;
    }
}
