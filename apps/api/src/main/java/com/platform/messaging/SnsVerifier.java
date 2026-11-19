package com.platform.messaging;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Verifies Amazon SNS message signatures (v1 SHA1 / v2 SHA256) using the AWS-hosted signing certificate. */
@Component
public class SnsVerifier {
    private static final Pattern CERT_HOST = Pattern.compile("^sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?$");
    private final ConcurrentHashMap<String, X509Certificate> certs = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public boolean verify(JsonNode m) {
        try {
            URI certUrl = URI.create(m.path("SigningCertURL").asString(""));
            if (!"https".equals(certUrl.getScheme()) || certUrl.getHost() == null || !CERT_HOST.matcher(certUrl.getHost()).matches()) return false;
            X509Certificate cert = certs.computeIfAbsent(certUrl.toString(), this::download);
            String type = m.path("Type").asString("");
            List<String> fields = "Notification".equals(type)
                    ? (m.hasNonNull("Subject") ? List.of("Message", "MessageId", "Subject", "Timestamp", "TopicArn", "Type") : List.of("Message", "MessageId", "Timestamp", "TopicArn", "Type"))
                    : List.of("Message", "MessageId", "SubscribeURL", "Timestamp", "Token", "TopicArn", "Type");
            StringBuilder sb = new StringBuilder();
            for (String f : fields) sb.append(f).append('\n').append(m.path(f).asString("")).append('\n');
            Signature sig = Signature.getInstance("2".equals(m.path("SignatureVersion").asString()) ? "SHA256withRSA" : "SHA1withRSA");
            sig.initVerify(cert.getPublicKey());
            sig.update(sb.toString().getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getDecoder().decode(m.path("Signature").asString("")));
        } catch (Exception e) {
            return false;
        }
    }

    private X509Certificate download(String url) {
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofInputStream());
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(r.body());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch SNS certificate", e);
        }
    }

    public void confirmSubscription(String subscribeUrl) {
        URI u = URI.create(subscribeUrl);
        if (!"https".equals(u.getScheme()) || !CERT_HOST.matcher(u.getHost()).matches()) throw new IllegalArgumentException("Untrusted SubscribeURL");
        try {
            http.send(HttpRequest.newBuilder(u).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
