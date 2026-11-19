package com.platform.consent;

import com.platform.contacts.Contact;
import com.platform.contacts.Phones;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Compliance gate evaluated for EVERY outbound message (automations, campaigns, tests, manual).
 * Deny by default: an unknown country/channel/purpose combination, a missing consent row or a
 * legal basis not accepted by the versioned policy matrix all block the send.
 * The matrix is a conservative baseline and does not replace legal review.
 */
@Service
public class PolicyEngine {
    private final JdbcTemplate jdbc;

    public PolicyEngine(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Decision(boolean allowed, String reason, boolean requiresUnsubscribe, boolean quietHoursEnforced, String recipientCountry) {
        static Decision block(String reason) { return new Decision(false, reason, false, false, null); }
    }

    public Decision evaluate(UUID orgId, Contact c, String channel, String purpose) {
        if (c == null) return Decision.block("NO_CONTACT");
        if (!"ACTIVE".equals(c.status())) return Decision.block("CONTACT_" + c.status());
        String consentChannel = "MMS".equals(channel) ? "SMS" : channel;
        String address = "EMAIL".equals(channel) ? c.email() : c.phoneE164();
        if (address == null) return Decision.block("NO_ADDRESS_FOR_CHANNEL");
        if ((channel.equals("SMS") || channel.equals("MMS") || channel.equals("WHATSAPP")) && !Phones.isMobileCapable(address)) {
            return Decision.block("NUMBER_NOT_MOBILE");
        }
        boolean promotional = "MARKETING".equals(purpose) || "REACTIVATION".equals(purpose);
        List<String> scopes = jdbc.queryForList("SELECT scope FROM suppression_list WHERE organization_id = ? AND channel = ? AND address = ?",
                String.class, orgId, consentChannel, address);
        if (scopes.contains("ALL") || (promotional && scopes.contains("MARKETING"))) return Decision.block("SUPPRESSED");

        String country = "EMAIL".equals(channel)
                ? (c.countryCode() != null ? c.countryCode() : orgCountry(orgId))
                : Phones.region(address).orElse(c.countryCode());
        if (country == null) return Decision.block("UNKNOWN_RECIPIENT_COUNTRY");
        List<Map<String, Object>> policy = jdbc.queryForList("""
                SELECT decision, accepted_bases, requires_unsubscribe, quiet_hours_enforced FROM compliance_policies
                WHERE country_code IN (?, '*') AND channel = ? AND purpose = ? AND effective_from <= now()
                ORDER BY policy_version DESC, (country_code = '*') ASC LIMIT 1""", country, consentChannel, purpose);
        if (policy.isEmpty()) return Decision.block("NO_POLICY_FOR_" + country + "_" + consentChannel + "_" + purpose);
        Map<String, Object> p = policy.getFirst();
        if ("BLOCK".equals(p.get("decision"))) return Decision.block("POLICY_BLOCKED_" + country);
        Set<String> bases = Arrays.stream(((String) p.get("accepted_bases")).split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        List<Map<String, Object>> consent = jdbc.queryForList("""
                SELECT status, legal_basis FROM contact_consents WHERE contact_id = ? AND channel = ? AND purpose = ?""",
                c.id(), consentChannel, purpose);
        if (consent.isEmpty()) return Decision.block("NO_CONSENT");
        if (!"GRANTED".equals(consent.getFirst().get("status"))) return Decision.block("CONSENT_" + consent.getFirst().get("status"));
        if (!bases.contains((String) consent.getFirst().get("legal_basis"))) return Decision.block("LEGAL_BASIS_NOT_ACCEPTED");
        return new Decision(true, "OK", (Boolean) p.get("requires_unsubscribe"), (Boolean) p.get("quiet_hours_enforced"), country);
    }

    private String orgCountry(UUID orgId) {
        return jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, orgId);
    }
}
