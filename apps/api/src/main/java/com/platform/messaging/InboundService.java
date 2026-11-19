package com.platform.messaging;

import com.platform.consent.ConsentService;
import com.platform.contacts.ContactDtos.ContactInput;
import com.platform.contacts.ContactEventService;
import com.platform.contacts.ContactRepository;
import com.platform.contacts.ContactService;
import com.platform.shared.Ids;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Inbound replies (WhatsApp, SMS): contact resolution, conversation, opt-out keywords, REPLY_RECEIVED event. */
@Service
public class InboundService {
    /** Opt-out keywords (EN/PT/ES/FR/DE). Carriers/Twilio also enforce STOP on US long codes. */
    static final Set<String> STOP_WORDS = Set.of("STOP", "STOPALL", "UNSUBSCRIBE", "END", "QUIT", "CANCEL", "PARAR", "SAIR", "CANCELAR",
            "DESCADASTRAR", "BAJA", "ARRET", "ARRÊT", "STOPP", "ABMELDEN");
    private final JdbcTemplate jdbc;
    private final ContactRepository contacts;
    private final ContactService contactService;
    private final ContactEventService events;
    private final ConsentService consents;

    public InboundService(JdbcTemplate jdbc, ContactRepository contacts, ContactService contactService, ContactEventService events, ConsentService consents) {
        this.jdbc = jdbc;
        this.contacts = contacts;
        this.contactService = contactService;
        this.events = events;
        this.consents = consents;
    }

    @Transactional
    public void receive(UUID orgId, String channel, String fromE164, String toAddress, String body, String provider, String providerMessageId, Instant at) {
        Integer dup = jdbc.queryForObject("SELECT count(*) FROM messages WHERE provider = ? AND provider_message_id = ?", Integer.class, provider, providerMessageId);
        if (dup != null && dup > 0) return;
        UUID contactId = contacts.findByPhone(orgId, fromE164).map(c -> c.id()).orElseGet(() ->
                contactService.upsert(orgId, new ContactInput(null, null, null, fromE164, null, null, null, null, null, null, null, null, null, null,
                        List.of("inbound"), null), "INBOUND", channel).getKey().id());
        UUID conv = jdbc.queryForObject("""
                INSERT INTO conversations (id, organization_id, contact_id, channel, status, last_inbound_at, last_message_at, unread_count)
                VALUES (?,?,?,?, 'OPEN', ?, ?, 1)
                ON CONFLICT (organization_id, contact_id, channel) DO UPDATE SET last_inbound_at = EXCLUDED.last_inbound_at,
                  last_message_at = EXCLUDED.last_message_at, unread_count = conversations.unread_count + 1, status = 'OPEN', updated_at = now()
                RETURNING id""", UUID.class, Ids.newId(), orgId, contactId, channel, Timestamp.from(at), Timestamp.from(at));
        jdbc.update("""
                INSERT INTO messages (id, organization_id, contact_id, conversation_id, direction, channel, purpose, status, to_address, from_address,
                  body, provider, provider_message_id, origin_type, idempotency_key, created_at)
                VALUES (?,?,?,?, 'INBOUND', ?, 'CUSTOMER_CARE', 'RECEIVED', ?, ?, ?, ?, ?, 'INBOUND', ?, ?)""",
                Ids.newId(), orgId, contactId, conv, channel, toAddress, fromE164, body, provider, providerMessageId,
                "in:" + provider + ":" + providerMessageId, Timestamp.from(at));
        // Most recent outbound to this contact on the channel within 7 days counts as replied.
        jdbc.update("""
                UPDATE messages SET status = 'REPLIED', updated_at = now() WHERE id = (
                  SELECT id FROM messages WHERE organization_id = ? AND contact_id = ? AND direction = 'OUTBOUND' AND channel IN (?, 'MMS')
                    AND status IN ('SUBMITTED','SENT','DELIVERED','READ') AND created_at > now() - interval '7 days' ORDER BY created_at DESC LIMIT 1)""",
                orgId, contactId, channel);
        String normalized = body == null ? "" : body.trim().toUpperCase(Locale.ROOT);
        if (STOP_WORDS.contains(normalized)) {
            consents.optOutMarketing(orgId, contactId, channel, fromE164, "INBOUND_STOP");
            events.record(orgId, contactId, "OPTED_OUT", at, channel, "optout:" + providerMessageId, Map.of("channel", channel));
            return;
        }
        events.record(orgId, contactId, "REPLY_RECEIVED", at, channel, "reply:" + providerMessageId, Map.of("channel", channel));
    }
}
