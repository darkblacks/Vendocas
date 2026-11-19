package com.platform.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * A request to send one message. {@code idempotencyKey} is unique per organization: replaying the
 * same request (worker restart, duplicate event) returns the existing message instead of a new send.
 */
public record SendRequest(UUID organizationId, UUID contactId, String channel, String purpose, UUID templateId, String subject,
                          String body, UUID mediaAssetId, String originType, UUID originId, String idempotencyKey, boolean test,
                          Instant notBefore, Integer frequencyCapDaysOverride) {}
