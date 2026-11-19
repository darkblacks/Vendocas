package com.platform.shared.outbox;

import java.util.Set;

public interface OutboxHandler {
    /** Stable consumer name used for inbox deduplication. */
    String consumer();

    Set<String> eventTypes();

    void handle(OutboxEvent event);
}
