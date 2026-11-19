package com.platform.messaging;

import com.platform.shared.crypto.Tokens;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** X-Twilio-Signature: base64(HMAC-SHA1(authToken, fullUrl + concat(sorted(key+value)))). */
public final class TwilioSignature {
    private TwilioSignature() {}

    public static String compute(String authToken, String url, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(url);
        new TreeMap<>(params).forEach((k, v) -> sb.append(k).append(v));
        return Base64.getEncoder().encodeToString(Tokens.hmac("HmacSHA1", authToken.getBytes(StandardCharsets.UTF_8), sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean valid(String authToken, String url, Map<String, String> params, String header) {
        return header != null && Tokens.constantTimeEquals(compute(authToken, url, params), header);
    }
}
