package com.platform.channels;

import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * SMS/Voice numbers through Twilio. Each organization gets its own Twilio subaccount so usage,
 * numbers and compliance registrations are isolated. US numbers require A2P 10DLC brand+campaign
 * approval before the sender is ACTIVE; the approval is read back from Twilio, not assumed.
 */
@Service
public class SmsChannelSetup {
    private final AppProperties props;
    private final ChannelService channels;
    private final JdbcTemplate jdbc;
    private final RestClient http;

    public SmsChannelSetup(AppProperties props, ChannelService channels, JdbcTemplate jdbc, RestClient.Builder builder) {
        this.props = props;
        this.channels = channels;
        this.jdbc = jdbc;
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(20));
        this.http = builder.clone().requestFactory(f).build();
    }

    private String auth() {
        if (!props.twilio().configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "SMS/Voice provider (Twilio) is not configured");
        return "Basic " + Base64.getEncoder().encodeToString((props.twilio().accountSid() + ":" + props.twilio().authToken()).getBytes(StandardCharsets.UTF_8));
    }

    private String api(String path) { return props.twilio().baseUrl() + "/2010-04-01" + path; }

    /** Creates (once) the organization's Twilio subaccount. */
    public String ensureSubaccount(UUID orgId) {
        var existing = channels.find(orgId, "SMS").map(c -> c.config().path("subaccountSid").asString(null)).orElse(null);
        if (existing != null) return existing;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("FriendlyName", "org-" + orgId);
        JsonNode res = post(api("/Accounts.json"), form);
        String sid = res.path("sid").asString();
        channels.upsert(orgId, "SMS", "TWILIO", "PENDING_VERIFICATION", "Subaccount created",
                "Choose a phone number", null, sid, Map.of("subaccountSid", sid));
        return sid;
    }

    public record AvailableNumber(String phoneNumber, String locality, String region, List<String> capabilities) {}

    public List<AvailableNumber> search(UUID orgId, String country, String areaCode) {
        String sub = ensureSubaccount(orgId);
        String q = "?SmsEnabled=true&PageSize=20" + (areaCode == null || areaCode.isBlank() ? "" : "&AreaCode=" + areaCode.replaceAll("\\D", ""));
        JsonNode res = http.get().uri(api("/Accounts/" + sub + "/AvailablePhoneNumbers/" + country + "/Local.json" + q))
                .header(HttpHeaders.AUTHORIZATION, auth()).retrieve().body(JsonNode.class);
        List<AvailableNumber> out = new ArrayList<>();
        for (JsonNode n : res.path("available_phone_numbers")) {
            List<String> caps = new ArrayList<>();
            n.path("capabilities").properties().forEach(e -> { if (e.getValue().asBoolean()) caps.add(e.getKey().toUpperCase(Locale.ROOT)); });
            out.add(new AvailableNumber(n.path("phone_number").asString(), n.path("locality").asString(null), n.path("region").asString(null), caps));
        }
        return out;
    }

    /** Buys a number in the org's subaccount and points SMS/voice webhooks at the platform. Billed to the wallet by ops policy. */
    public void purchase(UUID orgId, String e164) {
        String sub = ensureSubaccount(orgId);
        String country = jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, orgId);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("PhoneNumber", e164);
        form.add("SmsUrl", props.apiPublicUrl() + "/api/v1/webhooks/twilio/sms-inbound");
        form.add("VoiceUrl", props.apiPublicUrl() + "/api/v1/webhooks/twilio/voice-inbound");
        form.add("StatusCallback", props.apiPublicUrl() + "/api/v1/webhooks/twilio/voice-status");
        JsonNode res = post(api("/Accounts/" + sub + "/IncomingPhoneNumbers.json"), form);
        UUID conn = channels.find(orgId, "SMS").orElseThrow().id();
        boolean us = "US".equals(country);
        String senderStatus = us ? "PENDING_REGISTRATION" : "ACTIVE";
        Map<String, Object> verification = us
                ? Map.of("required", "A2P 10DLC", "steps", List.of("Register brand (business identity)", "Register campaign (use case, sample messages, opt-in flow)",
                        "Associate this number with the approved campaign's messaging service"))
                : Map.of();
        channels.upsertSender(orgId, conn, "SMS_NUMBER", e164, null, senderStatus, verification, res.path("sid").asString(), true);
        jdbc.update("""
                INSERT INTO phone_numbers (id, organization_id, e164, country_code, provider, provider_sid, ownership, capabilities, status)
                VALUES (?,?,?,?, 'TWILIO', ?, 'PURCHASED', 'SMS,VOICE', 'TEST_PENDING') ON CONFLICT (organization_id, e164) DO NOTHING""",
                com.platform.shared.Ids.newId(), orgId, e164, country, res.path("sid").asString());
        if (us) channels.setStatus(conn, "PENDING_APPROVAL", "A2P 10DLC registration required before sending to US numbers",
                "Submit brand and campaign registration; we check approval status automatically");
        else channels.setStatus(conn, "ACTIVE", "Number " + e164 + " ready", null);
    }

    /** Reads A2P campaign status of the configured messaging service from Twilio (US only). */
    public String refreshA2p(UUID orgId, String messagingServiceSid) {
        var c = channels.find(orgId, "SMS").orElseThrow(() -> ApiException.notFound("SMS channel"));
        JsonNode res = http.get().uri("https://messaging.twilio.com/v1/Services/" + messagingServiceSid + "/Compliance/Usa2p")
                .header(HttpHeaders.AUTHORIZATION, auth()).retrieve().body(JsonNode.class);
        String status = "UNKNOWN";
        for (JsonNode cmp : res.path("compliance")) status = cmp.path("campaign_status").asString(status);
        channels.upsert(orgId, "SMS", "TWILIO", c.status(), c.statusReason(), c.actionRequired(), null, null, Map.of("messagingServiceSid", messagingServiceSid));
        if ("VERIFIED".equalsIgnoreCase(status)) {
            jdbc.update("UPDATE sender_identities SET status = 'APPROVED', updated_at = now() WHERE connection_id = ? AND kind = 'SMS_NUMBER'", c.id());
            channels.setStatus(c.id(), "ACTIVE", "A2P 10DLC campaign verified", null);
            return "ACTIVE";
        }
        if ("FAILED".equalsIgnoreCase(status)) {
            jdbc.update("UPDATE sender_identities SET status = 'REJECTED', updated_at = now() WHERE connection_id = ? AND kind = 'SMS_NUMBER'", c.id());
            channels.setStatus(c.id(), "ERROR", "A2P campaign rejected", "Review the campaign description and resubmit");
            return "ERROR";
        }
        channels.setStatus(c.id(), "PENDING_APPROVAL", "A2P campaign status: " + status, "Waiting for carrier review");
        return "PENDING_APPROVAL";
    }

    private JsonNode post(String url, LinkedMultiValueMap<String, String> form) {
        try {
            return http.post().uri(url).header(HttpHeaders.AUTHORIZATION, auth()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String msg = com.platform.shared.Json.read(e.getResponseBodyAsString()).path("message").asString(e.getStatusText());
            throw new ApiException(ErrorCode.PROVIDER_ERROR, "Twilio: " + msg);
        }
    }
}
