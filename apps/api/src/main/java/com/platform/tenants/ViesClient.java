package com.platform.tenants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

/**
 * EU Commission VIES REST service. When VIES (or a member-state backend) is unavailable the
 * result is VERIFICATION_UNAVAILABLE — never "valid". Reverse charge is only applied to VERIFIED ids.
 */
@Component
public class ViesClient {
    private static final Logger log = LoggerFactory.getLogger(ViesClient.class);
    private final RestClient http;

    public ViesClient(RestClient.Builder builder) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.baseUrl("https://ec.europa.eu/taxation_customs/vies/rest-api").requestFactory(factory).build();
    }

    public record Result(String status, String reference) {}

    public Result check(String countryCode, String vatNumber) {
        String prefix = "GR".equals(countryCode) ? "EL" : countryCode;
        String body = vatNumber.startsWith(prefix) ? vatNumber.substring(prefix.length()) : vatNumber;
        try {
            JsonNode res = http.post().uri("/check-vat-number")
                    .body(Map.of("countryCode", prefix, "vatNumber", body))
                    .retrieve().body(JsonNode.class);
            if (res == null) return new Result("VERIFICATION_UNAVAILABLE", null);
            if (res.has("valid")) {
                return new Result(res.get("valid").asBoolean() ? "VERIFIED" : "INVALID", res.path("requestIdentifier").asString(null));
            }
            return new Result("VERIFICATION_UNAVAILABLE", null);
        } catch (RuntimeException e) {
            log.warn("VIES unavailable: {}", e.getMessage());
            return new Result("VERIFICATION_UNAVAILABLE", null);
        }
    }
}
