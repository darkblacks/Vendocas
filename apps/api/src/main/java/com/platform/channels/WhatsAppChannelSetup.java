package com.platform.channels;

import com.platform.audit.AuditService;
import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.shared.crypto.Tokens;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.*;

/**
 * WhatsApp Embedded Signup (Tech Provider flow). The frontend runs Meta's official popup with our
 * app id + configuration id; it returns an authorization code plus the WABA id and phone number
 * id chosen by the business. We exchange the code for a business token, subscribe our app to the
 * WABA webhooks, register the number (Cloud API) unless it is a coexistence (Business App) number,
 * and only then mark the channel ACTIVE according to the number status reported by Meta.
 */
@Service
public class WhatsAppChannelSetup {
    private final AppProperties props;
    private final ChannelService channels;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RestClient http;

    public WhatsAppChannelSetup(AppProperties props, ChannelService channels, JdbcTemplate jdbc, AuditService audit, RestClient.Builder builder) {
        this.props = props;
        this.channels = channels;
        this.jdbc = jdbc;
        this.audit = audit;
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(5));
        f.setReadTimeout(Duration.ofSeconds(30));
        this.http = builder.clone().requestFactory(f).build();
    }

    public record SignupConfig(boolean configured, String appId, String configId, String graphVersion) {}

    public SignupConfig config() {
        var m = props.meta();
        return new SignupConfig(m.configured() && m.embeddedSignupConfigId() != null && !m.embeddedSignupConfigId().isBlank(),
                m.appId(), m.embeddedSignupConfigId(), m.graphVersion());
    }

    private String graph(String path) { return props.meta().graphBaseUrl() + "/" + props.meta().graphVersion() + path; }

    public record CompleteRequest(String code, String wabaId, String phoneNumberId, boolean coexistence) {}

    public void complete(UUID orgId, UUID userId, CompleteRequest req) {
        if (!config().configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "WhatsApp Embedded Signup is not configured");
        if (req.code() == null || req.wabaId() == null || req.phoneNumberId() == null) throw ApiException.badRequest("Incomplete signup data");
        UUID conn = channels.upsert(orgId, "WHATSAPP", "META_CLOUD", "PENDING_AUTHORIZATION", "Exchanging authorization", null, null, req.wabaId(),
                Map.of("wabaId", req.wabaId(), "coexistence", req.coexistence()));
        try {
            JsonNode token = http.get().uri(graph("/oauth/access_token") + "?client_id={a}&client_secret={s}&code={c}",
                    props.meta().appId(), props.meta().appSecret(), req.code()).retrieve().body(JsonNode.class);
            String accessToken = token.path("access_token").asString(null);
            if (accessToken == null) throw ApiException.badRequest("Meta did not return an access token");
            channels.storeSecret(orgId, conn, "ACCESS_TOKEN", accessToken, null);

            http.post().uri(graph("/" + req.wabaId() + "/subscribed_apps")).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve().toBodilessEntity();
            if (!req.coexistence()) {
                String pin = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
                channels.storeSecret(orgId, conn, "TWO_STEP_PIN", pin, null);
                http.post().uri(graph("/" + req.phoneNumberId() + "/register")).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .body(Map.of("messaging_product", "whatsapp", "pin", pin)).retrieve().toBodilessEntity();
            }
            JsonNode phone = phoneInfo(req.phoneNumberId(), accessToken);
            String display = phone.path("display_phone_number").asString(null);
            String e164 = display == null ? req.phoneNumberId() : "+" + display.replaceAll("[^0-9]", "");
            boolean connected = "CONNECTED".equalsIgnoreCase(phone.path("status").asString(""))
                    || "CLOUD_API".equalsIgnoreCase(phone.path("platform_type").asString(""));
            channels.upsertSender(orgId, conn, "WHATSAPP_NUMBER", e164, phone.path("verified_name").asString(null),
                    connected ? "ACTIVE" : "PENDING_VERIFICATION", Map.of("codeVerification", phone.path("code_verification_status").asString("")),
                    req.phoneNumberId(), true);
            jdbc.update("UPDATE sender_identities SET quality_rating = ?, messaging_limit = ? WHERE organization_id = ? AND external_id = ?",
                    phone.path("quality_rating").asString(null), phone.path("messaging_limit_tier").asString(null), orgId, req.phoneNumberId());
            if (connected) channels.setStatus(conn, "ACTIVE", "Number connected to the Cloud API", "Create and submit message templates");
            else channels.setStatus(conn, "PENDING_VERIFICATION", "Number status: " + phone.path("status").asString("unknown"),
                    "Complete the number verification in WhatsApp Manager");
            audit.user(orgId, userId, "channel.whatsapp_connected", "channel", conn, Map.of("waba", req.wabaId()));
        } catch (RestClientResponseException e) {
            String msg = com.platform.shared.Json.read(e.getResponseBodyAsString()).path("error").path("message").asString(e.getStatusText());
            channels.setStatus(conn, "ERROR", "Meta: " + msg, "Retry the connection or check your WhatsApp Business Account");
            throw new ApiException(ErrorCode.PROVIDER_ERROR, "Meta rejected the request: " + msg);
        }
    }

    JsonNode phoneInfo(String phoneNumberId, String token) {
        return http.get().uri(graph("/" + phoneNumberId + "?fields=display_phone_number,verified_name,quality_rating,code_verification_status,platform_type,status,messaging_limit_tier"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(JsonNode.class);
    }

    /** Health check: refreshes number status/quality; token errors move the channel to ERROR. */
    public String refresh(UUID orgId) {
        var c = channels.find(orgId, "WHATSAPP").orElseThrow(() -> ApiException.notFound("WhatsApp channel"));
        String token = channels.secrets(c.id()).get("ACCESS_TOKEN");
        if (token == null) return c.status();
        var senders = channels.senders(orgId, c.id());
        if (senders.isEmpty()) return c.status();
        String pnid = jdbc.queryForObject("SELECT external_id FROM sender_identities WHERE id = ?", String.class, senders.getFirst().id());
        try {
            JsonNode p = phoneInfo(pnid, token);
            jdbc.update("UPDATE sender_identities SET quality_rating = ?, messaging_limit = ?, updated_at = now() WHERE id = ?",
                    p.path("quality_rating").asString(null), p.path("messaging_limit_tier").asString(null), senders.getFirst().id());
            String quality = p.path("quality_rating").asString("");
            String status = "RED".equalsIgnoreCase(quality) ? "DEGRADED" : "ACTIVE";
            channels.setStatus(c.id(), status, "Quality: " + quality, "RED".equalsIgnoreCase(quality) ? "Reduce promotional volume; Meta may limit this number" : null);
            return status;
        } catch (RestClientResponseException e) {
            channels.setStatus(c.id(), "ERROR", "Meta API error " + e.getStatusCode().value(), "Reconnect WhatsApp (token may be expired or revoked)");
            return "ERROR";
        }
    }

    // ---- templates ----
    public record TemplateSubmit(UUID templateId) {}

    /** Submits a local template to Meta for approval. Category and language come from the template row. */
    public void submitTemplate(UUID orgId, UUID templateId) {
        var c = channels.find(orgId, "WHATSAPP").filter(x -> "ACTIVE".equals(x.status()) || "DEGRADED".equals(x.status()))
                .orElseThrow(() -> ApiException.invalidState("Connect WhatsApp first"));
        var t = jdbc.queryForMap("SELECT * FROM message_templates WHERE id = ? AND organization_id = ? AND channel = 'WHATSAPP'", templateId, orgId);
        String name = ((String) t.get("name")).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        List<Map<String, Object>> components = new ArrayList<>();
        if (t.get("media_object_key") != null) components.add(Map.of("type", "HEADER", "format", "IMAGE"));
        String body = toMetaPlaceholders((String) t.get("body"));
        Map<String, Object> bodyComp = new LinkedHashMap<>(Map.of("type", "BODY", "text", body));
        int params = countParams(body);
        if (params > 0) {
            List<String> examples = new ArrayList<>();
            for (int i = 0; i < params; i++) examples.add("example" + (i + 1));
            bodyComp.put("example", Map.of("body_text", List.of(examples)));
        }
        components.add(bodyComp);
        Boolean alertsOnly = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM subscriptions s JOIN plan_features f ON f.plan_id = s.plan_id AND f.feature = 'ALERTS_ONLY'
                               WHERE s.organization_id = ? AND s.status NOT IN ('CANCELED','EXPIRED'))""", Boolean.class, orgId);
        if (Boolean.TRUE.equals(alertsOnly)) {
            // Alert plans: the approved template itself carries "only an alert — contact the store" (Meta footers allow no variables).
            String contact = jdbc.queryForObject("""
                    SELECT COALESCE(s.alert_contact, o.contact_phone, o.contact_email::text) FROM organizations o
                    LEFT JOIN organization_settings s ON s.organization_id = o.id WHERE o.id = ?""", String.class, orgId);
            components.add(Map.of("type", "FOOTER", "text", com.platform.markets.AlertFooter.whatsapp((String) t.get("locale"), contact)));
        } else if (Set.of("MARKETING", "REACTIVATION").contains((String) t.get("purpose"))) {
            components.add(Map.of("type", "FOOTER", "text", "Reply STOP to opt out"));
        }
        String token = channels.secrets(c.id()).get("ACCESS_TOKEN");
        try {
            JsonNode res = http.post().uri(graph("/" + c.externalAccountId() + "/message_templates")).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .body(Map.of("name", name, "language", ((String) t.get("locale")).replace('-', '_'), "category", t.get("wa_category"),
                            "components", components)).retrieve().body(JsonNode.class);
            jdbc.update("UPDATE message_templates SET status = 'SUBMITTED', updated_at = now() WHERE id = ?", templateId);
            jdbc.update("INSERT INTO template_approvals (id, organization_id, template_id, provider, provider_template_id, status) VALUES (?,?,?,?,?,?)",
                    com.platform.shared.Ids.newId(), orgId, templateId, "META_CLOUD", res.path("id").asString(null), res.path("status").asString("PENDING"));
        } catch (RestClientResponseException e) {
            String msg = com.platform.shared.Json.read(e.getResponseBodyAsString()).path("error").path("error_user_msg").asString(e.getStatusText());
            jdbc.update("UPDATE message_templates SET status = 'REJECTED', updated_at = now() WHERE id = ?", templateId);
            jdbc.update("INSERT INTO template_approvals (id, organization_id, template_id, provider, status, rejection_reason, decided_at) VALUES (?,?,?,?,?,?, now())",
                    com.platform.shared.Ids.newId(), orgId, templateId, "META_CLOUD", "REJECTED", msg);
            throw new ApiException(ErrorCode.PROVIDER_ERROR, "Template rejected by Meta: " + msg);
        }
    }

    /** {{contact.first_name}}-style variables become positional {{1}}, {{2}} as Meta requires. */
    public static String toMetaPlaceholders(String body) {
        var m = java.util.regex.Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}").matcher(body);
        StringBuilder sb = new StringBuilder();
        int i = 0;
        Map<String, Integer> seen = new LinkedHashMap<>();
        while (m.find()) {
            int n = seen.computeIfAbsent(m.group(1), k -> seen.size() + 1);
            m.appendReplacement(sb, "{{" + n + "}}");
            i++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Ordered distinct variable names, matching the positional parameters. */
    public static List<String> variableOrder(String body) {
        var m = java.util.regex.Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}").matcher(body);
        LinkedHashSet<String> s = new LinkedHashSet<>();
        while (m.find()) s.add(m.group(1));
        return List.copyOf(s);
    }

    private static int countParams(String metaBody) {
        var m = java.util.regex.Pattern.compile("\\{\\{(\\d+)}}").matcher(metaBody);
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return max;
    }

    static String hashForLog(String s) { return Tokens.sha256Hex(s).substring(0, 8); }
}
