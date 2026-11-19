package com.platform.automation.engine;

import com.platform.automation.model.AutomationDefinition;
import com.platform.shared.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Published versions are immutable, so they can be cached forever by id. */
@Component
public class VersionCache {
    private final JdbcTemplate jdbc;
    private final Map<UUID, AutomationDefinition> cache = new ConcurrentHashMap<>();

    public VersionCache(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public AutomationDefinition get(UUID versionId) {
        return cache.computeIfAbsent(versionId, id -> parse(jdbc.queryForObject(
                "SELECT definition::text FROM automation_versions WHERE id = ? AND published_at IS NOT NULL", String.class, id)));
    }

    public static AutomationDefinition parse(String json) { return Json.read(json, AutomationDefinition.class); }
}
