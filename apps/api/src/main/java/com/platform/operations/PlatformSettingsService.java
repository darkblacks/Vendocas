package com.platform.operations;

import com.platform.audit.AuditService;
import com.platform.operations.PlatformSettings.Environment;
import com.platform.operations.PlatformSettings.FiscalProvider;
import com.platform.operations.PlatformSettings.StripeMode;
import com.platform.shared.AppProperties;
import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;
import com.platform.taxation.FiscalDocumentProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source of truth for the environment / Stripe / fiscal switches (table platform_settings). Read on every decision
 * (cheap singleton row) so a change applies immediately on every instance; updates use optimistic concurrency and
 * are audited. Credentials stay where they are (environment of the API); only the switches live here.
 */
@Service
public class PlatformSettingsService {
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final AuditService audit;
    private final FiscalDocumentProvider fiscalProvider;

    public PlatformSettingsService(JdbcTemplate jdbc, AppProperties props, AuditService audit, FiscalDocumentProvider fiscalProvider) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
        this.fiscalProvider = fiscalProvider;
    }

    public PlatformSettings current() {
        return jdbc.queryForObject("""
                SELECT environment, stripe_mode, fiscal_automation, fiscal_provider, version, updated_by, updated_at
                FROM platform_settings WHERE id = 1""", (rs, i) -> new PlatformSettings(
                Environment.valueOf(rs.getString("environment")), StripeMode.valueOf(rs.getString("stripe_mode")),
                rs.getBoolean("fiscal_automation"), FiscalProvider.valueOf(rs.getString("fiscal_provider")), rs.getInt("version"),
                rs.getString("updated_by"), rs.getTimestamp("updated_at").toInstant()));
    }

    public record Update(Environment environment, StripeMode stripeMode, Boolean fiscalAutomation, FiscalProvider fiscalProvider,
                         Integer expectedVersion) {}

    /** Partial update; {@code expectedVersion} (when given) must match, otherwise CONFLICT — no lost updates. */
    @Transactional
    public PlatformSettings update(Update u, String actorLabel, String actorType, java.util.UUID actorUserId) {
        jdbc.queryForObject("SELECT version FROM platform_settings WHERE id = 1 FOR UPDATE", Integer.class); // serialize writers
        PlatformSettings before = current();
        if (u.expectedVersion() != null && before.version() != u.expectedVersion()) {
            throw new ApiException(ErrorCode.CONFLICT, "Settings were changed by someone else (version " + before.version() + "); reload and try again");
        }
        PlatformSettings next = new PlatformSettings(
                u.environment() != null ? u.environment() : before.environment(),
                u.stripeMode() != null ? u.stripeMode() : before.stripeMode(),
                u.fiscalAutomation() != null ? u.fiscalAutomation() : before.fiscalAutomation(),
                u.fiscalProvider() != null ? u.fiscalProvider() : before.fiscalProvider(),
                before.version() + 1, actorLabel, null);
        jdbc.update("""
                UPDATE platform_settings SET environment=?, stripe_mode=?, fiscal_automation=?, fiscal_provider=?, version=?, updated_by=?, updated_at=?
                WHERE id = 1""", next.environment().name(), next.stripeMode().name(), next.fiscalAutomation(), next.fiscalProvider().name(),
                next.version(), actorLabel, Timestamp.from(java.time.Instant.now()));
        Map<String, Object> changes = new LinkedHashMap<>();
        if (before.environment() != next.environment()) changes.put("environment", before.environment() + "→" + next.environment());
        if (before.stripeMode() != next.stripeMode()) changes.put("stripeMode", before.stripeMode() + "→" + next.stripeMode());
        if (before.fiscalAutomation() != next.fiscalAutomation()) changes.put("fiscalAutomation", before.fiscalAutomation() + "→" + next.fiscalAutomation());
        if (before.fiscalProvider() != next.fiscalProvider()) changes.put("fiscalProvider", before.fiscalProvider() + "→" + next.fiscalProvider());
        changes.put("actor", actorLabel);
        audit.record(null, actorUserId, actorType, "platform.settings.updated", "platform_settings", 1, changes, null);
        return current();
    }

    // ------------------------------------------------------------------ integration status (what is really active)

    public record ServiceStatus(String key, String label, String state, String detail) {}

    public record Status(PlatformSettings settings, String stripeKeyMode, boolean stripeConfigured, boolean stripeWebhookConfigured,
                         boolean stripeModeMatches, boolean focusConfigured, String focusEnvironment, String fiscalMode,
                         Map<String, Long> fiscalDocuments, List<ServiceStatus> services, List<String> warnings) {}

    /** Effective state of payments and fiscal issuance, for the Control Center and the admin API. Never exposes keys. */
    public Status status() {
        PlatformSettings s = current();
        StripeKeyMode.Mode keyMode = StripeKeyMode.of(props.stripe() == null ? null : props.stripe().secretKey());
        boolean stripeConfigured = keyMode != StripeKeyMode.Mode.NONE;
        boolean webhook = props.stripe() != null && props.stripe().webhookSecret() != null && !props.stripe().webhookSecret().isBlank();
        boolean matches = keyMode.name().equals(s.stripeMode().name());
        boolean focus = fiscalProvider.configured(); // same source the issuance job uses
        String focusEnv = props.focusNfe() == null ? null : ("producao".equals(props.focusNfe().environment()) ? "PRODUCTION" : "HOMOLOGATION");
        String fiscalMode = !s.fiscalAutomation() ? "MANUAL"
                : s.fiscalProvider() == FiscalProvider.NONE ? "AUTOMATIC_WITHOUT_PROVIDER"
                : focus ? "AUTOMATIC" : "AUTOMATIC_PROVIDER_NOT_CONFIGURED";
        Map<String, Long> docs = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) AS n FROM tax_documents GROUP BY status ORDER BY status",
                rs -> { docs.put(rs.getString("status"), rs.getLong("n")); });

        List<ServiceStatus> services = new ArrayList<>();
        services.add(new ServiceStatus("environment", "Ambiente", s.environment().name(), null));
        services.add(new ServiceStatus("stripe", "Pagamentos (Stripe)",
                !stripeConfigured ? "NOT_CONFIGURED" : matches ? "ACTIVE" : "BLOCKED_MODE_MISMATCH",
                "modo " + s.stripeMode() + (stripeConfigured ? ", chave " + keyMode : "")));
        services.add(new ServiceStatus("stripe_webhook", "Webhooks do Stripe", webhook ? "ACTIVE" : "NOT_CONFIGURED", null));
        services.add(new ServiceStatus("fiscal_automation", "Emissão fiscal automática",
                switch (fiscalMode) { case "AUTOMATIC" -> "ACTIVE"; case "MANUAL" -> "DISABLED"; default -> "MISCONFIGURED"; }, fiscalMode));
        services.add(new ServiceStatus("fiscal_provider", "Provedor fiscal",
                s.fiscalProvider() == FiscalProvider.NONE ? "NONE" : focus ? "CONFIGURED" : "NOT_CONFIGURED",
                s.fiscalProvider() == FiscalProvider.NONE ? null : "Focus NFe" + (focusEnv == null ? "" : " (" + focusEnv + ")")));

        List<String> warnings = new ArrayList<>();
        if (stripeConfigured && !matches) warnings.add("A chave do Stripe é " + keyMode + " mas o modo selecionado é " + s.stripeMode()
                + ": checkout, mudanças de plano e portal ficam bloqueados até alinhar (webhooks continuam sendo processados).");
        if (s.environment() == Environment.HOMOLOGATION && s.stripeMode() == StripeMode.LIVE)
            warnings.add("Homologação com Stripe em produção: as cobranças são reais.");
        if (s.environment() == Environment.PRODUCTION && s.stripeMode() == StripeMode.TEST)
            warnings.add("Produção com Stripe em modo de teste: nenhuma cobrança real será feita.");
        if ("AUTOMATIC_WITHOUT_PROVIDER".equals(fiscalMode)) warnings.add("Emissão automática ativada sem provedor fiscal: nada será emitido.");
        if ("AUTOMATIC_PROVIDER_NOT_CONFIGURED".equals(fiscalMode)) warnings.add("Focus NFe selecionada, mas FOCUS_NFE_TOKEN não está configurado na API.");
        if (!s.fiscalAutomation()) warnings.add("Emissão fiscal automática desativada: as obrigações fiscais continuam existindo; emita manualmente os documentos pendentes.");
        if (focus && s.environment() == Environment.PRODUCTION && "HOMOLOGATION".equals(focusEnv) && s.automaticIssuance())
            warnings.add("Produção usando o ambiente de homologação da Focus NFe: notas emitidas não têm validade fiscal.");
        return new Status(s, keyMode.name(), stripeConfigured, webhook, matches, focus, focusEnv, fiscalMode, docs, services, warnings);
    }
}
