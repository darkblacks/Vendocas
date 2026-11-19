package com.platform.notifications;

import com.platform.shared.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-app notifications + email to the relevant members. Emails are best-effort (never break the caller's transaction). */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private final JdbcTemplate jdbc;
    private final PlatformEmails emails;

    public NotificationService(JdbcTemplate jdbc, PlatformEmails emails) {
        this.jdbc = jdbc;
        this.emails = emails;
    }

    public record NotificationView(UUID id, String severity, String code, String title, String body, boolean read, java.time.Instant createdAt) {}

    /** Returns false if a notification with the same dedupe key already exists. */
    public boolean notify(UUID orgId, String severity, String code, String title, String body, String dedupeKey) {
        return jdbc.update("""
                INSERT INTO system_notifications (id, organization_id, severity, code, title, body, dedupe_key)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (dedupe_key) DO NOTHING""", Ids.newId(), orgId, severity, code, title, body, dedupeKey) == 1;
    }

    public void notifyBilling(UUID orgId, String code, String title, String body) {
        notify(orgId, "CRITICAL", code, title, body, null);
        List<Map<String, Object>> recipients = jdbc.queryForList("""
                SELECT DISTINCT u.email, u.locale FROM organization_members m JOIN users u ON u.id = m.user_id
                WHERE m.organization_id = ? AND m.status = 'ACTIVE' AND m.role IN ('OWNER','FINANCE')
                UNION SELECT finance_email, locale FROM organizations WHERE id = ? AND finance_email IS NOT NULL""", orgId, orgId);
        for (var r : recipients) {
            try {
                emails.notice((String) r.get("email"), (String) r.get("locale"), title, body);
            } catch (RuntimeException e) {
                log.warn("Billing notice email failed for org {}: {}", orgId, e.getMessage());
            }
        }
    }

    public List<NotificationView> list(UUID orgId, boolean unreadOnly) {
        return jdbc.query("""
                SELECT id, severity, code, title, body, read_at IS NOT NULL, created_at FROM system_notifications
                WHERE organization_id = ? AND (? = false OR read_at IS NULL) ORDER BY created_at DESC LIMIT 100""",
                (rs, i) -> new NotificationView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant()), orgId, unreadOnly);
    }

    public void markRead(UUID orgId, UUID id) {
        jdbc.update("UPDATE system_notifications SET read_at = now() WHERE organization_id = ? AND id = ?", orgId, id);
    }
}
