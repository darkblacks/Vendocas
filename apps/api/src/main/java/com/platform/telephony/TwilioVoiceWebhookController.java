package com.platform.telephony;

import com.platform.messaging.TwilioSignature;
import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Twilio Voice webhooks. Two routing modes per number:
 *  - FORWARD_TO_BUSINESS: callers dial the Vendocas number; we &lt;Dial&gt; the business line with a
 *    timeout; the dial result tells us if it was answered.
 *  - CONDITIONAL_FORWARD_IN: the business line forwards unanswered/busy calls to the Vendocas
 *    number (carrier "conditional call forwarding"); every call arriving is therefore a missed call.
 * Calls are never recorded (no &lt;Record&gt; verb is ever emitted).
 */
@RestController
@RequestMapping("/api/v1/webhooks/twilio")
@Hidden
public class TwilioVoiceWebhookController {
    private final AppProperties props;
    private final JdbcTemplate jdbc;
    private final CallService calls;

    public TwilioVoiceWebhookController(AppProperties props, JdbcTemplate jdbc, CallService calls) {
        this.props = props;
        this.jdbc = jdbc;
        this.calls = calls;
    }

    @PostMapping(value = "/voice-inbound", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public String inbound(@RequestParam Map<String, String> p, HttpServletRequest req) {
        verify(p, req);
        var num = number(p.get("To"));
        if (num == null) return twiml("<Reject/>");
        String mode = (String) num.get("mode");
        if ("FORWARD_TO_BUSINESS".equals(mode) && num.get("forward_to_e164") != null) {
            calls.record(event(num, p, "RINGING"));
            String action = props.apiPublicUrl() + "/api/v1/webhooks/twilio/voice-dial-result";
            return twiml("<Dial timeout=\"20\" action=\"" + HtmlUtils.htmlEscape(action) + "\">" + HtmlUtils.htmlEscape((String) num.get("forward_to_e164")) + "</Dial>");
        }
        // Conditional forwarding: the business did not answer.
        calls.record(event(num, p, "MISSED"));
        return twiml("<Say language=\"" + sayLang((String) num.get("locale")) + "\">" + HtmlUtils.htmlEscape(sorry((String) num.get("locale"))) + "</Say><Hangup/>");
    }

    @PostMapping(value = "/voice-dial-result", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public String dialResult(@RequestParam Map<String, String> p, HttpServletRequest req) {
        verify(p, req);
        var num = number(p.get("To"));
        if (num == null) return twiml("<Hangup/>");
        String st = switch (p.getOrDefault("DialCallStatus", "")) {
            case "completed" -> "COMPLETED";
            case "busy" -> "BUSY";
            case "no-answer" -> "NO_ANSWER";
            case "canceled" -> "CANCELED";
            default -> "FAILED";
        };
        var ev = event(num, p, st);
        Integer duration = p.containsKey("DialCallDuration") ? Integer.valueOf(p.get("DialCallDuration")) : null;
        calls.record(new CallService.CallEvent(ev.orgId(), ev.phoneNumberId(), ev.provider(), ev.providerCallId(), ev.direction(), ev.from(), ev.to(), st,
                duration, null, Instant.now()));
        return "COMPLETED".equals(st) ? twiml("<Hangup/>")
                : twiml("<Say language=\"" + sayLang((String) num.get("locale")) + "\">" + HtmlUtils.htmlEscape(sorry((String) num.get("locale"))) + "</Say><Hangup/>");
    }

    @PostMapping(value = "/voice-status", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public void status(@RequestParam Map<String, String> p, HttpServletRequest req) {
        verify(p, req);
        var num = number(p.get("To"));
        if (num == null) return;
        String st = p.getOrDefault("CallStatus", "").toUpperCase(Locale.ROOT).replace('-', '_');
        if ("COMPLETED".equals(st)) {
            var ev = event(num, p, st);
            calls.record(new CallService.CallEvent(ev.orgId(), ev.phoneNumberId(), ev.provider(), ev.providerCallId(), ev.direction(), ev.from(), ev.to(), st,
                    p.containsKey("CallDuration") ? Integer.valueOf(p.get("CallDuration")) : null, null, Instant.now()));
        }
    }

    private CallService.CallEvent event(Map<String, Object> num, Map<String, String> p, String status) {
        return new CallService.CallEvent((UUID) num.get("organization_id"), (UUID) num.get("id"), "TWILIO", p.get("CallSid"), "INBOUND",
                p.get("From"), p.get("To"), status, null, Instant.now(), null);
    }

    private Map<String, Object> number(String to) {
        var rows = jdbc.queryForList("""
                SELECT n.id, n.organization_id, n.forward_to_e164, o.locale,
                       CASE WHEN n.ownership = 'FORWARDED' THEN 'CONDITIONAL_FORWARD_IN' ELSE 'FORWARD_TO_BUSINESS' END AS mode
                FROM phone_numbers n JOIN organizations o ON o.id = n.organization_id
                WHERE n.e164 = ? AND n.provider = 'TWILIO' AND n.status IN ('TEST_PENDING','MONITORING','VERIFIED')""", to);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void verify(Map<String, String> params, HttpServletRequest req) {
        if (!props.twilio().configured()) throw new ApiException(ErrorCode.PROVIDER_NOT_CONFIGURED, "Twilio not configured");
        String url = props.apiPublicUrl() + req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        if (!TwilioSignature.valid(props.twilio().authToken(), url, params, req.getHeader("X-Twilio-Signature"))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid Twilio signature");
        }
    }

    private static String twiml(String inner) { return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>" + inner + "</Response>"; }

    private static String sayLang(String locale) {
        return switch (com.platform.shared.i18n.Languages.resolve(null, locale)) {
            case "pt" -> "pt-BR"; case "es" -> "es-ES"; case "fr" -> "fr-FR"; case "de" -> "de-DE"; case "it" -> "it-IT"; default -> "en-US";
        };
    }

    private static String sorry(String locale) {
        return switch (com.platform.shared.i18n.Languages.resolve(null, locale)) {
            case "pt" -> "Não conseguimos atender agora. Retornaremos sua ligação em breve.";
            case "es" -> "No podemos atender ahora. Le devolveremos la llamada pronto.";
            case "fr" -> "Nous ne pouvons pas répondre pour le moment. Nous vous rappellerons très vite.";
            case "de" -> "Wir können gerade nicht rangehen. Wir rufen Sie bald zurück.";
            case "it" -> "Non possiamo rispondere ora. La richiameremo presto.";
            default -> "We could not answer right now. We will call you back soon.";
        };
    }
}
