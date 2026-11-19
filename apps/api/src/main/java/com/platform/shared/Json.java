package com.platform.shared;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared Jackson 3 mapper for JSONB payloads (java.time is supported natively in Jackson 3). */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private Json() {}

    public static String write(Object value) { return MAPPER.writeValueAsString(value); }

    public static JsonNode read(String json) { return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json); }

    public static <T> T read(String json, Class<T> type) { return MAPPER.readValue(json, type); }

    public static <T> T convert(Object value, Class<T> type) { return MAPPER.convertValue(value, type); }

    public static ObjectNode object() { return MAPPER.createObjectNode(); }
}
