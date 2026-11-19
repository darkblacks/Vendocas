package com.platform.automation.engine;

import com.platform.shared.Json;
import com.platform.shared.api.ApiException;
import com.platform.shared.crypto.SecretCipher;
import com.platform.shared.crypto.Tokens;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Authenticated outbound webhook to the organization's own system. Requests are signed with the
 * organization's tracking secret (header X-Vendocas-Signature: sha256=HMAC(body)) and carry an
 * Idempotency-Key; private/loopback destinations are refused (SSRF protection).
 * Delivery is at-least-once: receivers must deduplicate on Idempotency-Key.
 */
@Component
public class WebhookCaller {
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public WebhookCaller(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public String call(UUID orgId, String url, String idempotencyKey, Map<String, ?> payload) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme())) throw ApiException.badRequest("Webhook must use https");
        assertPublic(uri.getHost());
        String body = Json.write(payload);
        String secret = cipher.decrypt(jdbc.queryForObject("SELECT tracking_secret_enc FROM organizations WHERE id = ?", String.class, orgId));
        try {
            HttpResponse<Void> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("Idempotency-Key", idempotencyKey)
                    .header("X-Vendocas-Signature", "sha256=" + Tokens.hmacSha256Hex(secret, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() >= 500 || res.statusCode() == 429) throw new IllegalStateException("Webhook returned " + res.statusCode());
            return "Webhook HTTP " + res.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Webhook unreachable: " + e.getMessage());
        }
    }

    static void assertPublic(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress() || a.isMulticastAddress()
                        || a.getHostAddress().startsWith("100.64.") || a.getHostAddress().startsWith("fc") || a.getHostAddress().startsWith("fd")) {
                    throw ApiException.badRequest("Webhook destination must be a public address");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw ApiException.badRequest("Webhook host does not resolve");
        }
    }
}
