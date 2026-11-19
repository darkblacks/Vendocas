package com.platform.channels;

import com.platform.billing.EntitlementService;
import com.platform.messaging.providers.MessageProvider;
import com.platform.shared.Ids;
import com.platform.shared.Json;
import com.platform.shared.api.ApiException;
import com.platform.shared.crypto.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Channel connections and their honest state. A connection is ACTIVE only after the provider
 * confirmed it (domain verified, number registered, test passed) — never because a user typed data.
 */
@Service
public class ChannelService {
    public static final List<String> CHANNELS = List.of("EMAIL", "WHATSAPP", "SMS", "VOICE");
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final EntitlementService entitlements;

    public ChannelService(JdbcTemplate jdbc, SecretCipher cipher, EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.entitlements = entitlements;
    }

    public record ConnectionRow(UUID id, String channel, String provider, String status, String statusReason, String actionRequired,
                                String displayName, String externalAccountId, JsonNode config, Instant lastVerifiedAt) {}

    public record SenderView(UUID id, String kind, String value, String displayName, String status, JsonNode verificationData,
                             String qualityRating, String messagingLimit, boolean isDefault) {}

    public record Capability(String capability, boolean available, String requires, String notes) {}

    public record ChannelView(String channel, boolean includedInPlan, String status, String statusReason, String actionRequired,
                              String provider, String displayName, Instant lastVerifiedAt, List<SenderView> senders, List<Capability> capabilities) {}

    public List<ChannelView> overview(UUID orgId) {
        var e = entitlements.of(orgId);
        String country = jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, orgId);
        List<ChannelView> out = new ArrayList<>();
        for (String ch : CHANNELS) {
            Optional<ConnectionRow> c = find(orgId, ch);
            List<Capability> caps = jdbc.query("SELECT capability, available, requires, notes FROM channel_capabilities WHERE country_code = ? AND channel = ? ORDER BY capability",
                    (rs, i) -> new Capability(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4)), country, ch);
            out.add(new ChannelView(ch, e.features().contains(EntitlementService.featureForChannel(ch)),
                    c.map(ConnectionRow::status).orElse("NOT_CONFIGURED"), c.map(ConnectionRow::statusReason).orElse(null),
                    c.map(ConnectionRow::actionRequired).orElse(caps.isEmpty() ? "Not available in your country yet" : null),
                    c.map(ConnectionRow::provider).orElse(null), c.map(ConnectionRow::displayName).orElse(null),
                    c.map(ConnectionRow::lastVerifiedAt).orElse(null), c.map(x -> senders(orgId, x.id())).orElse(List.of()), caps));
        }
        return out;
    }

    public Optional<ConnectionRow> find(UUID orgId, String channel) {
        return jdbc.query("""
                SELECT id, channel, provider, status, status_reason, action_required, display_name, external_account_id, config::text, last_verified_at
                FROM channel_connections WHERE organization_id = ? AND channel = ?""",
                (rs, i) -> new ConnectionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), Json.read(rs.getString(9)),
                        rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()), orgId, channel).stream().findFirst();
    }

    public Optional<ConnectionRow> findByExternalAccount(String channel, String externalAccountId) {
        return jdbc.query("""
                SELECT id, channel, provider, status, status_reason, action_required, display_name, external_account_id, config::text, last_verified_at, organization_id
                FROM channel_connections WHERE channel = ? AND external_account_id = ?""",
                (rs, i) -> new ConnectionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), Json.read(rs.getString(9)), null), channel, externalAccountId).stream().findFirst();
    }

    @Transactional
    public UUID upsert(UUID orgId, String channel, String provider, String status, String reason, String action, String displayName,
                       String externalAccountId, Map<String, ?> config) {
        return jdbc.queryForObject("""
                INSERT INTO channel_connections (id, organization_id, channel, provider, status, status_reason, action_required, display_name, external_account_id, config)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (organization_id, channel) DO UPDATE SET provider = EXCLUDED.provider, status = EXCLUDED.status,
                  status_reason = EXCLUDED.status_reason, action_required = EXCLUDED.action_required,
                  display_name = COALESCE(EXCLUDED.display_name, channel_connections.display_name),
                  external_account_id = COALESCE(EXCLUDED.external_account_id, channel_connections.external_account_id),
                  config = channel_connections.config || EXCLUDED.config, updated_at = now(), version = channel_connections.version + 1
                RETURNING id""", UUID.class, Ids.newId(), orgId, channel, provider, status, reason, action, displayName, externalAccountId,
                Json.write(config == null ? Map.of() : config));
    }

    @Transactional
    public void setStatus(UUID connectionId, String status, String reason, String action) {
        jdbc.update("""
                UPDATE channel_connections SET status = ?, status_reason = ?, action_required = ?, updated_at = now(),
                  last_verified_at = CASE WHEN ? = 'ACTIVE' THEN now() ELSE last_verified_at END,
                  last_error_at = CASE WHEN ? IN ('ERROR','DEGRADED') THEN now() ELSE last_error_at END WHERE id = ?""",
                status, reason, action, status, status, connectionId);
    }

    @Transactional
    public void storeSecret(UUID orgId, UUID connectionId, String kind, String value, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO channel_credentials (id, organization_id, connection_id, kind, secret_enc, expires_at) VALUES (?,?,?,?,?,?)
                ON CONFLICT (connection_id, kind) DO UPDATE SET secret_enc = EXCLUDED.secret_enc, expires_at = EXCLUDED.expires_at, rotated_at = now()""",
                Ids.newId(), orgId, connectionId, kind, cipher.encrypt(value), expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    public Map<String, String> secrets(UUID connectionId) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT kind, secret_enc FROM channel_credentials WHERE connection_id = ?", rs -> {
            m.put(rs.getString(1), cipher.decrypt(rs.getString(2)));
        }, connectionId);
        return m;
    }

    @Transactional
    public UUID upsertSender(UUID orgId, UUID connectionId, String kind, String value, String displayName, String status,
                             Map<String, ?> verificationData, String externalId, boolean isDefault) {
        if (isDefault) jdbc.update("UPDATE sender_identities SET is_default = false WHERE connection_id = ?", connectionId);
        return jdbc.queryForObject("""
                INSERT INTO sender_identities (id, organization_id, connection_id, kind, value, display_name, status, verification_data, external_id, is_default)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
                ON CONFLICT (organization_id, kind, value) DO UPDATE SET status = EXCLUDED.status, display_name = COALESCE(EXCLUDED.display_name, sender_identities.display_name),
                  verification_data = EXCLUDED.verification_data, external_id = COALESCE(EXCLUDED.external_id, sender_identities.external_id),
                  is_default = EXCLUDED.is_default, connection_id = EXCLUDED.connection_id, updated_at = now()
                RETURNING id""", UUID.class, Ids.newId(), orgId, connectionId, kind, value, displayName, status,
                Json.write(verificationData == null ? Map.of() : verificationData), externalId, isDefault);
    }

    public List<SenderView> senders(UUID orgId, UUID connectionId) {
        return jdbc.query("""
                SELECT id, kind, value, display_name, status, verification_data::text, quality_rating, messaging_limit, is_default
                FROM sender_identities WHERE organization_id = ? AND connection_id = ? ORDER BY is_default DESC, created_at""",
                (rs, i) -> new SenderView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        Json.read(rs.getString(6)), rs.getString(7), rs.getString(8), rs.getBoolean(9)), orgId, connectionId);
    }

    /** What the dispatcher needs to send for this org/channel, or empty when the channel is not usable. */
    public Optional<Sendable> sendable(UUID orgId, String channel) {
        String ch = "MMS".equals(channel) ? "SMS" : channel;
        Optional<ConnectionRow> c = find(orgId, ch).filter(x -> "ACTIVE".equals(x.status()) || "DEGRADED".equals(x.status()));
        if (c.isEmpty()) return Optional.empty();
        var sender = jdbc.queryForList("""
                SELECT value, display_name, external_id FROM sender_identities WHERE connection_id = ? AND status IN ('ACTIVE','APPROVED')
                ORDER BY is_default DESC LIMIT 1""", c.get().id());
        if (sender.isEmpty()) return Optional.empty();
        var s = sender.getFirst();
        return Optional.of(new Sendable(new MessageProvider.Connection(c.get().id(), orgId, c.get().externalAccountId(), c.get().config(),
                secrets(c.get().id()), (String) s.get("external_id")), c.get().provider(), (String) s.get("value"), (String) s.get("display_name")));
    }

    public record Sendable(MessageProvider.Connection connection, String provider, String fromAddress, String fromName) {}

    @Transactional
    public void disconnect(UUID orgId, String channel) {
        var c = find(orgId, channel).orElseThrow(() -> ApiException.notFound("Channel"));
        jdbc.update("DELETE FROM channel_credentials WHERE connection_id = ?", c.id());
        setStatus(c.id(), "DISCONNECTED", "Disconnected by the organization", "Connect again to resume sending");
    }
}
