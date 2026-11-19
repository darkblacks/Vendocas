package com.platform.automation;

import com.platform.automation.engine.ConditionEvaluator;
import com.platform.automation.engine.InstanceFactory;
import com.platform.automation.model.*;
import com.platform.billing.UsageRates;
import com.platform.contacts.Contact;
import com.platform.contacts.ContactRepository;
import com.platform.messaging.MessageService;
import com.platform.messaging.SendRequest;
import com.platform.messaging.TemplateRenderer;
import com.platform.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Dry-run tools. Simulation NEVER writes messages or reserves credits: it walks the graph with
 * computed timestamps, evaluates conditions and renders previews. Test runs send REAL messages but
 * only to contacts explicitly tagged "test-recipient" by the organization, through all compliance gates.
 */
@Service
public class AutomationSimulator {
    public static final String TEST_TAG = "test-recipient";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ContactRepository contacts;
    private final ConditionEvaluator conditions;
    private final MessageService messages;
    private final UsageRates rates;

    public AutomationSimulator(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ContactRepository contacts, ConditionEvaluator conditions,
                               MessageService messages, UsageRates rates) {
        this.jdbc = jdbc;
        this.named = named;
        this.contacts = contacts;
        this.conditions = conditions;
        this.messages = messages;
        this.rates = rates;
    }

    public record SampleContact(String name, String email, String phone, String countryCode, List<String> tags) {}

    public record SimulationStep(String stepId, String type, Instant at, String outcome, String detail, String previewSubject, String previewBody) {}

    public List<SimulationStep> simulate(UUID org, AutomationDefinition def, UUID contactId, SampleContact sample, Instant start) {
        DefinitionValidator.require(def);
        ZoneId zone = ZoneId.of(jdbc.queryForObject("SELECT time_zone FROM organizations WHERE id = ?", String.class, org));
        boolean fictitious = contactId == null;
        Contact c = fictitious
                ? new Contact(UUID.randomUUID(), org, null, sample == null ? "Alex Example" : sample.name(), sample == null ? "alex@example.com" : sample.email(),
                        sample == null ? null : sample.phone(), null, sample == null ? null : sample.countryCode(), null, null, null, null, null, null, null, null,
                        "ACTIVE", "SIMULATION", Map.of(), sample == null || sample.tags() == null ? List.of() : sample.tags(), Instant.now(), Instant.now())
                : contacts.find(org, contactId).orElseThrow(() -> ApiException.notFound("Contact"));
        Map<String, AutomationDefinition.Step> byId = new HashMap<>();
        def.steps().forEach(s -> byId.put(s.id(), s));
        Instant t = start == null ? Instant.now() : start;
        String trade = jdbc.queryForObject("SELECT trade_name FROM organizations WHERE id = ?", String.class, org);
        List<SimulationStep> out = new ArrayList<>();
        out.add(new SimulationStep("trigger", def.trigger().type().name(), t,
                InstanceFactory.matchesAudience(def.trigger().audience(), c.tags(), c.countryCode()) ? "ENTERS" : "NOT_IN_AUDIENCE", null, null, null));
        String stepId = def.steps().getFirst().id();
        for (int i = 0; i < 50 && stepId != null; i++) {
            var s = byId.get(stepId);
            switch (s.type()) {
                case WAIT -> {
                    t = TimeRules.add(t, s.duration(), zone);
                    out.add(new SimulationStep(s.id(), "WAIT", t, "WAITED", s.duration().amount() + " " + s.duration().unit(), null, null));
                    stepId = DefinitionValidator.nextOf(def, s);
                }
                case WAIT_UNTIL -> {
                    t = TimeRules.nextLocalTime(t, LocalTime.parse(s.time()), zone, Boolean.TRUE.equals(s.businessDaysOnly()));
                    out.add(new SimulationStep(s.id(), "WAIT_UNTIL", t, "WAITED", s.time(), null, null));
                    stepId = DefinitionValidator.nextOf(def, s);
                }
                case CONDITION -> {
                    var ctx = new ConditionEvaluator.Ctx(org, c, start == null ? Instant.now() : start, t, zone, null);
                    List<String> details = new ArrayList<>();
                    boolean all = !"ANY".equals(s.match());
                    boolean result = all;
                    for (var cond : s.conditions()) {
                        boolean r = fictitious && "CONSENT".equals(cond.type()) || conditions.eval(cond, ctx);
                        details.add(cond.type() + "=" + r + (fictitious && "CONSENT".equals(cond.type()) ? " (assumed for sample contact)" : ""));
                        result = all ? result && r : result || r;
                    }
                    out.add(new SimulationStep(s.id(), "CONDITION", t, result ? "TRUE" : "FALSE", String.join(", ", details), null, null));
                    String next = result ? s.onTrue() : s.onFalse();
                    stepId = next == null || "end".equals(next) ? null : next;
                }
                case SEND -> {
                    Map<String, Object> vars = new HashMap<>(c.variables());
                    vars.put("business.name", trade);
                    String subject = s.subject(), body = s.body();
                    if (s.templateId() != null) {
                        var tpl = jdbc.queryForList("SELECT subject, body FROM message_templates WHERE id = ? AND organization_id = ?", s.templateId(), org);
                        if (!tpl.isEmpty()) { subject = (String) tpl.getFirst().get("subject"); body = (String) tpl.getFirst().get("body"); }
                    }
                    String outcome, detail;
                    if (fictitious) {
                        outcome = "WOULD_SEND";
                        detail = "Sample contact: consent and channel checks are assumed";
                    } else {
                        var ev = messages.evaluate(new SendRequest(org, c.id(), s.channel(), s.purpose(), s.templateId(), s.subject(), s.body(),
                                s.mediaAssetId(), "SIMULATION", null, "sim", false, null, null));
                        outcome = ev.sendable() ? "WOULD_SEND" : "WOULD_BE_BLOCKED";
                        detail = ev.sendable() ? "Estimated cost " + ev.estimatedCostMinor() + " " + ev.currency() : ev.reason();
                    }
                    out.add(new SimulationStep(s.id(), "SEND " + s.channel(), t, outcome, detail, TemplateRenderer.render(subject, vars, false),
                            TemplateRenderer.render(body, vars, false)));
                    stepId = DefinitionValidator.nextOf(def, s);
                }
                case END -> { out.add(new SimulationStep(s.id(), "END", t, "END", null, null, null)); stepId = null; }
                default -> {
                    out.add(new SimulationStep(s.id(), s.type().name(), t, "WOULD_EXECUTE", Optional.ofNullable(s.title()).orElse(s.tag()), null, null));
                    stepId = DefinitionValidator.nextOf(def, s);
                }
            }
        }
        return out;
    }

    public record Estimate(long audience, List<ChannelEstimate> channels, String note) {}

    public record ChannelEstimate(String stepId, String channel, String purpose, long reachable, long unitCostMinor, long totalCostMinor, String currency,
                                  boolean priced) {}

    /** Current audience size and, per send step, how many have address+consent and the estimated cost (upper bound). */
    public Estimate estimate(UUID org, AutomationDefinition def) {
        MapSqlParameterSource p = new MapSqlParameterSource("org", org);
        String audienceSql = InstanceFactory.audienceSql(def.trigger().audience(), p);
        String extra = "";
        if (def.trigger().type() == TriggerType.CONTACT_INACTIVE && def.trigger().period() != null) {
            ZoneId zone = ZoneId.of(jdbc.queryForObject("SELECT time_zone FROM organizations WHERE id = ?", String.class, org));
            String basis = switch (def.trigger().basis() == null ? "" : def.trigger().basis()) {
                case "LAST_PURCHASE" -> "c.last_purchase_at";
                case "LAST_VISIT" -> "c.last_visit_at";
                default -> "COALESCE(c.last_interaction_at, c.last_purchase_at, c.created_at)";
            };
            p.addValue("threshold", java.sql.Timestamp.from(TimeRules.subtract(Instant.now(), def.trigger().period(), zone)));
            extra = " AND " + basis + " <= :threshold";
        }
        long audience = Optional.ofNullable(named.queryForObject("SELECT count(*) FROM contacts c WHERE c.organization_id = :org" + audienceSql + extra, p, Long.class)).orElse(0L);
        String currency = jdbc.queryForObject("SELECT currency FROM credit_wallets WHERE organization_id = ?", String.class, org);
        String country = jdbc.queryForObject("SELECT country_code FROM organizations WHERE id = ?", String.class, org);
        List<ChannelEstimate> list = new ArrayList<>();
        for (var s : def.steps()) {
            if (s.type() != StepType.SEND) continue;
            String addr = "EMAIL".equals(s.channel()) ? "c.email::text" : "c.phone_e164";
            MapSqlParameterSource q = new MapSqlParameterSource(p.getValues()).addValue("ch", s.channel()).addValue("pu", s.purpose());
            long reachable = Optional.ofNullable(named.queryForObject("""
                    SELECT count(*) FROM contacts c WHERE c.organization_id = :org""" + audienceSql + extra + " AND " + addr + """
                     IS NOT NULL AND EXISTS (SELECT 1 FROM contact_consents cc WHERE cc.contact_id = c.id AND cc.channel = :ch AND cc.purpose = :pu AND cc.status = 'GRANTED')
                      AND NOT EXISTS (SELECT 1 FROM suppression_list sl WHERE sl.organization_id = c.organization_id AND sl.channel = :ch AND sl.address = """ + addr + ")",
                    q, Long.class)).orElse(0L);
            String category = "WHATSAPP".equals(s.channel()) ? "MARKETING" : "ANY";
            if (s.templateId() != null) category = jdbc.queryForList("SELECT COALESCE(wa_category,'ANY') FROM message_templates WHERE id = ?", String.class, s.templateId()).stream().findFirst().orElse(category);
            var rate = rates.find(s.channel(), country, category, currency);
            long unit = rate.map(UsageRates.Rate::unitPriceMinor).orElse(0L);
            list.add(new ChannelEstimate(s.id(), s.channel(), s.purpose(), reachable, unit, unit * reachable, currency, rate.isPresent()));
        }
        return new Estimate(audience, list, "Upper bound: conditions, frequency caps and later opt-outs reduce the real volume. Unpriced channels cannot send.");
    }

    public record TestResult(String stepId, String channel, String status, String reason) {}

    public List<TestResult> testRun(UUID org, AutomationDefinition def, UUID automationId, UUID contactId) {
        Contact c = contacts.find(org, contactId).orElseThrow(() -> ApiException.notFound("Contact"));
        if (!c.tags().contains(TEST_TAG)) throw ApiException.badRequest("Test messages can only go to contacts tagged \"" + TEST_TAG + "\"");
        List<TestResult> out = new ArrayList<>();
        String run = UUID.randomUUID().toString().substring(0, 8);
        for (var s : def.steps()) {
            if (s.type() != StepType.SEND) continue;
            var r = messages.enqueue(new SendRequest(org, contactId, s.channel(), s.purpose(), s.templateId(), s.subject(), s.body(), s.mediaAssetId(),
                    "TEST", automationId, "test:" + automationId + ":" + s.id() + ":" + run, true, null, null));
            out.add(new TestResult(s.id(), s.channel(), r.status(), r.reason()));
        }
        return out;
    }
}
