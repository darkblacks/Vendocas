package com.platform.messaging.providers;

import com.platform.shared.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;

/**
 * Twilio Programmable Messaging (SMS/MMS). Each organization has its own Twilio subaccount
 * (config.subaccountSid) and its own registered sender (number or messaging service).
 */
@Component
public class TwilioSmsProvider implements MessageProvider {
    /** 21610 = recipient replied STOP; 21211/21614 invalid / not mobile; 21408 region not enabled. */
    private static final Set<Integer> OPTED_OUT = Set.of(21610);
    private final AppProperties props;
    private final RestClient http;

    public TwilioSmsProvider(AppProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = HttpClients.withTimeouts(builder, Duration.ofSeconds(5), Duration.ofSeconds(20)).build();
    }

    @Override public String provider() { return "TWILIO"; }
    @Override public String channel() { return "SMS"; }
    @Override public boolean configured() { return props.twilio() != null && props.twilio().configured(); }

    String auth() {
        return "Basic " + Base64.getEncoder().encodeToString((props.twilio().accountSid() + ":" + props.twilio().authToken()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Result send(Outbound m, Connection c) {
        String account = c.config().path("subaccountSid").asString(props.twilio().accountSid());
        var form = new LinkedMultiValueMap<String, String>();
        form.add("To", m.to());
        String service = c.config().path("messagingServiceSid").asString(null);
        if (service != null) form.add("MessagingServiceSid", service); else form.add("From", m.from());
        form.add("Body", m.text());
        if (m.mediaUrl() != null) form.add("MediaUrl", m.mediaUrl());
        form.add("StatusCallback", props.apiPublicUrl() + "/api/v1/webhooks/twilio/sms-status");
        try {
            JsonNode res = http.post().uri(props.twilio().baseUrl() + "/2010-04-01/Accounts/" + account + "/Messages.json")
                    .header(HttpHeaders.AUTHORIZATION, auth()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(JsonNode.class);
            return Result.accepted(res.path("sid").asString());
        } catch (RestClientResponseException e) {
            JsonNode err = com.platform.shared.Json.read(e.getResponseBodyAsString());
            int code = err.path("code").asInt(-1);
            String msg = err.path("message").asString(e.getStatusText());
            if (OPTED_OUT.contains(code)) return new Result(Outcome.PERMANENT, null, "TWILIO_" + code, msg, true, false);
            if (e.getStatusCode().value() == 401) return new Result(Outcome.PERMANENT, null, "TWILIO_401", msg, false, true);
            if (e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError()) return Result.retryable("TWILIO_" + code, msg);
            return Result.permanent("TWILIO_" + code, msg);
        } catch (ResourceAccessException e) {
            return Result.retryable("NETWORK", e.getMessage());
        }
    }
}
