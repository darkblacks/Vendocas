package com.platform.billing;

import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Answers "may this organization use feature X right now?" from the live subscription. */
@Service
public class EntitlementService {
    public static final String CHANNEL_EMAIL = "CHANNEL_EMAIL", CHANNEL_WHATSAPP = "CHANNEL_WHATSAPP", CHANNEL_SMS = "CHANNEL_SMS",
            CHANNEL_VOICE = "CHANNEL_VOICE", CAMPAIGNS_DAILY = "CAMPAIGNS_DAILY", AUTOMATIONS = "AUTOMATIONS", REPORTS = "REPORTS";
    private final JdbcTemplate jdbc;

    public EntitlementService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Entitlements(SubscriptionStatus status, String planCode, Set<String> features) {
        public boolean serviceActive() { return status != null && status.entitlesService(); }
        public boolean has(String feature) { return serviceActive() && features.contains(feature); }
    }

    public Entitlements of(UUID orgId) {
        var rows = jdbc.queryForList("""
                SELECT s.status, p.code, p.id FROM subscriptions s JOIN plans p ON p.id = s.plan_id
                WHERE s.organization_id = ? AND s.status NOT IN ('CANCELED','EXPIRED')""", orgId);
        if (rows.isEmpty()) return new Entitlements(null, null, Set.of());
        Map<String, Object> r = rows.getFirst();
        Set<String> features = new HashSet<>(jdbc.queryForList("SELECT feature FROM plan_features WHERE plan_id = ?", String.class, r.get("id")));
        return new Entitlements(SubscriptionStatus.valueOf((String) r.get("status")), (String) r.get("code"), features);
    }

    public void require(UUID orgId, String feature) {
        Entitlements e = of(orgId);
        if (!e.serviceActive()) throw new ApiException(ErrorCode.SUBSCRIPTION_INACTIVE, "An active subscription is required");
        if (!e.features().contains(feature)) {
            throw new ApiException(ErrorCode.PLAN_FEATURE_REQUIRED, "Your plan does not include " + feature, Map.of("feature", feature));
        }
    }

    public static String featureForChannel(String channel) {
        return switch (channel) {
            case "EMAIL" -> CHANNEL_EMAIL;
            case "WHATSAPP" -> CHANNEL_WHATSAPP;
            case "SMS", "MMS" -> CHANNEL_SMS;
            case "VOICE" -> CHANNEL_VOICE;
            default -> throw new IllegalArgumentException(channel);
        };
    }
}
