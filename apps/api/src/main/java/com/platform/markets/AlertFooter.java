package com.platform.markets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fixed footer of the alert plans (Alerta and Alerta + Vendas): those plans do not hold conversations, so every message
 * says which store sent it, that it is only an alert (no need to reply) and how to reach the store — e-mail and phone.
 * Text follows the customer's language.
 */
public final class AlertFooter {
    /** WhatsApp template footers are limited to 60 characters and cannot contain variables. */
    public static final int WHATSAPP_MAX = 60;

    /** SQL fragment (aliases o = organizations, s = organization_settings) returning store, e-mail and phone. */
    public static final String SQL_COLUMNS = "o.trade_name AS footer_store, COALESCE(s.alert_email, o.contact_email::text) AS footer_email, "
            + "COALESCE(s.alert_contact, o.contact_phone) AS footer_phone";

    public record Contact(String store, String email, String phone) {
        public Contact {
            store = clean(store); email = clean(email); phone = clean(phone);
        }
        public static Contact of(java.util.Map<String, Object> row) {
            return new Contact((String) row.get("footer_store"), (String) row.get("footer_email"), (String) row.get("footer_phone"));
        }
    }

    private AlertFooter() {}

    private record L(String sentBy, String generic, String reach, String email, String phone, String smsBy, String smsGeneric, String waBy, String waGeneric) {}

    private static L labels(String locale) {
        return switch (language(locale)) {
            case "pt" -> new L("Este alerta foi enviado por %s. É apenas um alerta, não precisa responder.", "Esta mensagem é apenas um alerta, não precisa responder.",
                    "Para falar com a loja:", "E-mail", "Telefone", "Alerta de %s, não responda.", "Apenas um alerta, não responda.", "Alerta de %s. Não responda.", "Apenas um alerta. Não responda.");
            case "es" -> new L("Este aviso fue enviado por %s. Es solo un aviso, no hace falta responder.", "Este mensaje es solo un aviso, no hace falta responder.",
                    "Para hablar con la tienda:", "Correo", "Teléfono", "Aviso de %s, no respondas.", "Solo un aviso, no respondas.", "Aviso de %s. No respondas.", "Solo un aviso. No respondas.");
            case "fr" -> new L("Cette alerte a été envoyée par %s. C'est uniquement une alerte, inutile de répondre.", "Ce message est uniquement une alerte, inutile de répondre.",
                    "Pour contacter la boutique :", "E-mail", "Téléphone", "Alerte de %s, ne répondez pas.", "Simple alerte, ne répondez pas.", "Alerte de %s. Ne répondez pas.", "Simple alerte. Ne répondez pas.");
            case "it" -> new L("Questo avviso è stato inviato da %s. È solo un avviso, non serve rispondere.", "Questo messaggio è solo un avviso, non serve rispondere.",
                    "Per parlare con il negozio:", "E-mail", "Telefono", "Avviso di %s, non rispondere.", "Solo un avviso, non rispondere.", "Avviso di %s. Non rispondere.", "Solo un avviso. Non rispondere.");
            case "de" -> new L("Dieser Hinweis wurde von %s gesendet. Es ist nur ein Hinweis, bitte nicht antworten.", "Diese Nachricht ist nur ein Hinweis, bitte nicht antworten.",
                    "Kontakt zum Geschäft:", "E-Mail", "Telefon", "Hinweis von %s, nicht antworten.", "Nur ein Hinweis, nicht antworten.", "Hinweis von %s. Nicht antworten.", "Nur ein Hinweis. Nicht antworten.");
            default -> new L("This alert was sent by %s. It is only an alert, no need to reply.", "This message is only an alert, no need to reply.",
                    "To reach the store:", "Email", "Phone", "Alert from %s, do not reply.", "Only an alert, do not reply.", "Alert from %s. Do not reply.", "Only an alert. Do not reply.");
        };
    }

    /** E-mail and WhatsApp free text: store on top, then "only an alert", then e-mail and phone on their own lines. */
    public static String text(String locale, Contact c) {
        L l = labels(locale);
        List<String> lines = new ArrayList<>();
        lines.add(c.store() == null ? l.generic() : l.sentBy().formatted(c.store()));
        if (c.email() != null || c.phone() != null) {
            lines.add(l.reach());
            if (c.email() != null) lines.add(l.email() + ": " + c.email());
            if (c.phone() != null) lines.add(l.phone() + ": " + c.phone());
        }
        return String.join("\n", lines);
    }

    /** SMS: one short line (every character costs). Phone first, e-mail when there is no phone. */
    public static String sms(String locale, Contact c) {
        L l = labels(locale);
        String head = c.store() == null ? l.smsGeneric() : l.smsBy().formatted(c.store());
        String contact = c.phone() != null ? c.phone() : c.email();
        return contact == null ? head : head + " " + l.reach() + " " + contact;
    }

    /** WhatsApp template footer (≤ 60 characters, no variables): drops parts that do not fit. */
    public static String whatsapp(String locale, Contact c) {
        L l = labels(locale);
        List<String> candidates = new ArrayList<>();
        if (c.store() != null) {
            String by = l.waBy().formatted(c.store());
            if (c.phone() != null) candidates.add(by + " " + c.phone());
            if (c.email() != null) candidates.add(by + " " + c.email());
            candidates.add(by);
        }
        if (c.phone() != null) candidates.add(l.waGeneric() + " " + c.phone());
        if (c.email() != null) candidates.add(l.waGeneric() + " " + c.email());
        for (String s : candidates) if (s.length() <= WHATSAPP_MAX) return s;
        return l.waGeneric();
    }

    static String language(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        return locale.replace('_', '-').split("-")[0].toLowerCase(Locale.ROOT);
    }

    private static String clean(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
