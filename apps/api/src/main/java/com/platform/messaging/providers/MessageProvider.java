package com.platform.messaging.providers;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Outbound provider port. Adapters translate provider errors into retryable / permanent outcomes. */
public interface MessageProvider {
    String provider();

    String channel();

    boolean configured();

    record Outbound(UUID messageId, String to, String from, String fromName, String replyTo, String subject, String text, String html,
                    String mediaUrl, Template template, Map<String, String> headers) {}

    /** WhatsApp template reference with ordered body parameters. */
    record Template(String name, String language, List<String> bodyParams, String headerImageUrl) {}

    /** Credentials/config for the organization's connection; secrets already decrypted in memory only. */
    record Connection(UUID connectionId, UUID organizationId, String externalAccountId, JsonNode config, Map<String, String> secrets,
                      String senderExternalId) {}

    enum Outcome { ACCEPTED, RETRYABLE, PERMANENT }

    record Result(Outcome outcome, String providerMessageId, String errorCode, String errorMessage, boolean recipientOptedOut,
                  boolean credentialsInvalid) {
        public static Result accepted(String id) { return new Result(Outcome.ACCEPTED, id, null, null, false, false); }
        public static Result retryable(String code, String msg) { return new Result(Outcome.RETRYABLE, null, code, msg, false, false); }
        public static Result permanent(String code, String msg) { return new Result(Outcome.PERMANENT, null, code, msg, false, false); }
    }

    Result send(Outbound message, Connection connection);
}
