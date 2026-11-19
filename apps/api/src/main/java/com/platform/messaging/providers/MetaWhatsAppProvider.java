package com.platform.messaging.providers;

import com.platform.shared.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.*;

/**
 * WhatsApp Business Platform — Cloud API (official). Sends through the organization's own phone
 * number id with the business token obtained via Embedded Signup. Business-initiated messages
 * must use approved templates; free-form text only inside the 24h customer-service window (the
 * window check happens before enqueue; Meta's 131047 error is mapped as a permanent failure).
 */
@Component
public class MetaWhatsAppProvider implements MessageProvider {
    private static final Set<Integer> RETRYABLE = Set.of(1, 2, 4, 80007, 130429, 131000, 131016, 133004);
    private static final Set<Integer> TOKEN = Set.of(190, 10, 200);
    private final AppProperties props;
    private final RestClient http;

    public MetaWhatsAppProvider(AppProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = HttpClients.withTimeouts(builder, Duration.ofSeconds(5), Duration.ofSeconds(20)).build();
    }

    @Override public String provider() { return "META_CLOUD"; }
    @Override public String channel() { return "WHATSAPP"; }
    @Override public boolean configured() { return props.meta() != null && props.meta().configured(); }

    String graph(String path) { return props.meta().graphBaseUrl() + "/" + props.meta().graphVersion() + path; }

    @Override
    public Result send(Outbound m, Connection c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", m.to().replace("+", ""));
        if (m.template() != null) {
            List<Map<String, Object>> components = new ArrayList<>();
            if (m.template().headerImageUrl() != null) {
                components.add(Map.of("type", "header", "parameters", List.of(Map.of("type", "image", "image", Map.of("link", m.template().headerImageUrl())))));
            }
            if (!m.template().bodyParams().isEmpty()) {
                components.add(Map.of("type", "body", "parameters", m.template().bodyParams().stream().map(p -> Map.of("type", "text", "text", p)).toList()));
            }
            body.put("type", "template");
            body.put("template", Map.of("name", m.template().name(), "language", Map.of("code", m.template().language()), "components", components));
        } else if (m.mediaUrl() != null) {
            body.put("type", "image");
            body.put("image", Map.of("link", m.mediaUrl(), "caption", m.text() == null ? "" : m.text()));
        } else {
            body.put("type", "text");
            body.put("text", Map.of("body", m.text(), "preview_url", true));
        }
        try {
            JsonNode res = http.post().uri(graph("/" + c.senderExternalId() + "/messages"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + c.secrets().get("ACCESS_TOKEN"))
                    .body(body).retrieve().body(JsonNode.class);
            String id = res == null ? null : res.path("messages").path(0).path("id").asString(null);
            return id == null ? Result.retryable("NO_ID", "Response without message id") : Result.accepted(id);
        } catch (RestClientResponseException e) {
            JsonNode err = com.platform.shared.Json.read(e.getResponseBodyAsString()).path("error");
            int code = err.path("code").asInt(-1);
            String msg = err.path("message").asString(e.getStatusText());
            if (TOKEN.contains(code)) return new Result(Outcome.PERMANENT, null, "META_" + code, msg, false, true);
            if (RETRYABLE.contains(code) || e.getStatusCode().is5xxServerError()) return Result.retryable("META_" + code, msg);
            return Result.permanent("META_" + code, msg);
        } catch (ResourceAccessException e) {
            return Result.retryable("NETWORK", e.getMessage());
        }
    }
}
