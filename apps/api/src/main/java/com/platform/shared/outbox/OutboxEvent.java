package com.platform.shared.outbox;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record OutboxEvent(UUID id, UUID organizationId, String aggregateType, UUID aggregateId, String eventType, JsonNode payload) {}
