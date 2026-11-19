package com.platform.shared.jobs;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public record Job(UUID id, String type, UUID organizationId, JsonNode payload, int attempts, int maxAttempts) {}
