package com.platform.conversations;

import com.platform.messaging.MessageService;
import com.platform.messaging.SendRequest;
import com.platform.security.RequiresPermission;
import com.platform.security.TenantContext;
import com.platform.shared.api.ApiException;
import com.platform.tenants.Permission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "conversations")
public class ConversationController {
    private final JdbcTemplate jdbc;
    private final MessageService messages;

    public ConversationController(JdbcTemplate jdbc, MessageService messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public record ConversationView(UUID id, UUID contactId, String contactName, String contactAddress, String channel, String status,
                                   UUID assignedTo, Instant lastInboundAt, Instant lastMessageAt, int unreadCount, boolean serviceWindowOpen) {}

    @GetMapping
    @RequiresPermission(Permission.CONVERSATIONS_READ)
    public List<ConversationView> inbox(@RequestParam(defaultValue = "OPEN") String status) {
        return jdbc.query("""
                SELECT v.id, v.contact_id, c.name, COALESCE(c.phone_e164, c.email::text), v.channel, v.status, v.assigned_to, v.last_inbound_at,
                       v.last_message_at, v.unread_count
                FROM conversations v JOIN contacts c ON c.id = v.contact_id
                WHERE v.organization_id = ? AND v.status = ? ORDER BY v.last_message_at DESC NULLS LAST LIMIT 200""",
                (rs, i) -> {
                    Instant li = rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant();
                    return new ConversationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), li,
                            rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(), rs.getInt(10),
                            li != null && li.isAfter(Instant.now().minusSeconds(86400)));
                }, TenantContext.current().orgId(), status);
    }

    public record MessageView(UUID id, String direction, String channel, String status, String statusReason, String subject, String body,
                              Instant createdAt) {}

    @GetMapping("/{id}/messages")
    @RequiresPermission(Permission.CONVERSATIONS_READ)
    public List<MessageView> messages(@PathVariable UUID id) {
        UUID org = TenantContext.current().orgId();
        jdbc.update("UPDATE conversations SET unread_count = 0 WHERE id = ? AND organization_id = ?", id, org);
        return jdbc.query("""
                SELECT m.id, m.direction, m.channel, m.status, m.status_reason, m.subject, m.body, m.created_at FROM messages m
                JOIN conversations v ON v.organization_id = m.organization_id AND v.contact_id = m.contact_id
                  AND (m.channel = v.channel OR (v.channel = 'SMS' AND m.channel = 'MMS'))
                WHERE v.id = ? AND v.organization_id = ? AND NOT m.is_test ORDER BY m.created_at LIMIT 500""",
                (rs, i) -> new MessageView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant()), id, org);
    }

    public record ReplyRequest(@NotBlank @Size(max = 4000) String body, UUID templateId) {}

    /** Human reply. Purpose CUSTOMER_CARE; on WhatsApp free text only inside the 24h service window (enforced by MessageService). */
    @PostMapping("/{id}/reply")
    @RequiresPermission(Permission.CONVERSATIONS_REPLY)
    public MessageService.EnqueueResult reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest r) {
        var t = TenantContext.current();
        var conv = jdbc.queryForList("SELECT contact_id, channel FROM conversations WHERE id = ? AND organization_id = ?", id, t.orgId());
        if (conv.isEmpty()) throw ApiException.notFound("Conversation");
        var res = messages.enqueue(new SendRequest(t.orgId(), (UUID) conv.getFirst().get("contact_id"), (String) conv.getFirst().get("channel"),
                "CUSTOMER_CARE", r.templateId(), "Re:", r.body(), null, "MANUAL", id, "reply:" + id + ":" + UUID.randomUUID(), false, null, null));
        jdbc.update("UPDATE conversations SET status = 'PENDING', assigned_to = COALESCE(assigned_to, ?), updated_at = now() WHERE id = ?", t.userId(), id);
        return res;
    }

    public record UpdateRequest(String status, UUID assignedTo) {}

    @PatchMapping("/{id}")
    @RequiresPermission(Permission.CONVERSATIONS_REPLY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable UUID id, @RequestBody UpdateRequest r) {
        UUID org = TenantContext.current().orgId();
        if (r.status() != null && !List.of("OPEN", "PENDING", "CLOSED").contains(r.status())) throw ApiException.badRequest("Invalid status");
        if (r.assignedTo() != null) {
            Integer member = jdbc.queryForObject("SELECT count(*) FROM organization_members WHERE organization_id = ? AND user_id = ?", Integer.class, org, r.assignedTo());
            if (member == null || member == 0) throw ApiException.badRequest("Assignee is not a member");
        }
        jdbc.update("UPDATE conversations SET status = COALESCE(?, status), assigned_to = COALESCE(?, assigned_to), updated_at = now() WHERE id = ? AND organization_id = ?",
                r.status(), r.assignedTo(), id, org);
    }
}
