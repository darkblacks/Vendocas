package com.platform.consent;

import com.platform.shared.AppProperties;
import com.platform.shared.crypto.Tokens;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/** Signed, non-guessable unsubscribe links: base64url(org|contact|channel).hmac */
@Component
public class UnsubscribeTokens {
    private final String key;
    private final AppProperties props;

    public UnsubscribeTokens(AppProperties props) {
        this.props = props;
        this.key = "unsub:" + props.security().jwtSecret();
    }

    public record Target(UUID orgId, UUID contactId, String channel) {}

    public String token(UUID orgId, UUID contactId, String channel) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString((orgId + "|" + contactId + "|" + channel).getBytes(StandardCharsets.UTF_8));
        return payload + "." + Tokens.hmacSha256Hex(key, payload).substring(0, 32);
    }

    public String url(UUID orgId, UUID contactId, String channel) {
        return props.apiPublicUrl() + "/api/v1/public/unsubscribe/" + token(orgId, contactId, channel);
    }

    public Optional<Target> verify(String token) {
        int dot = token.lastIndexOf('.');
        if (dot < 0) return Optional.empty();
        String payload = token.substring(0, dot);
        if (!Tokens.constantTimeEquals(Tokens.hmacSha256Hex(key, payload).substring(0, 32), token.substring(dot + 1))) return Optional.empty();
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8).split("\\|");
            return Optional.of(new Target(UUID.fromString(p[0]), UUID.fromString(p[1]), p[2]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
