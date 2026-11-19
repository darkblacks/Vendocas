package com.platform.shared;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Typed application configuration. Every provider block is optional: when a credential is
 * absent the corresponding adapter reports {@code configured() == false} and the feature is
 * shown as NOT_CONFIGURED instead of being simulated.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String publicUrl,
        String apiPublicUrl,
        List<String> corsAllowedOrigins,
        Security security,
        Mail mail,
        Stripe stripe,
        Seller seller,
        Aws aws,
        Meta meta,
        Twilio twilio,
        FocusNfe focusNfe,
        Storage storage,
        Workers workers) {

    public record Security(String jwtSecret, String credentialsEncryptionKey,
                           Duration accessTokenTtl, Duration refreshTokenTtl, String cookieDomain, boolean secureCookies) {}

    /** mode: "ses" (production) or "log" (local development only — prints links to the log). */
    public record Mail(String mode, String fromAddress, String fromName, String platformDomain) {}

    public record Stripe(String secretKey, String webhookSecret, boolean automaticTax, int invoiceDaysUntilDue) {
        public boolean configured() { return secretKey != null && !secretKey.isBlank(); }
    }

    /** The legal entity that sells the subscription (drives fiscal documents). */
    public record Seller(String legalName, String countryCode, String taxId, String municipalRegistration,
                         String serviceCode, String municipalityCode) {}

    public record Aws(String region, String accessKeyId, String secretAccessKey, String sesConfigurationSet,
                      String snsTopicArn) {
        public boolean configured() { return region != null && !region.isBlank(); }
    }

    public record Meta(String appId, String appSecret, String embeddedSignupConfigId, String webhookVerifyToken,
                       String graphVersion, String graphBaseUrl) {
        public boolean configured() { return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank(); }
    }

    public record Twilio(String accountSid, String authToken, String baseUrl) {
        public boolean configured() { return accountSid != null && !accountSid.isBlank() && authToken != null && !authToken.isBlank(); }
    }

    public record FocusNfe(String token, String environment) {
        public boolean configured() { return token != null && !token.isBlank(); }
        public String baseUrl() {
            return "producao".equals(environment) ? "https://api.focusnfe.com.br" : "https://homologacao.focusnfe.com.br";
        }
    }

    public record Storage(String endpoint, String region, String bucket, String accessKey, String secretKey) {
        public boolean configured() { return bucket != null && !bucket.isBlank(); }
    }

    public record Workers(boolean enabled, int batchSize, Duration leaseDuration) {}
}
