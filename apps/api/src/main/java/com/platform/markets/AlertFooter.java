package com.platform.markets;

import java.util.Locale;

/**
 * Fixed footer of the alert plans (Alerta and Alerta + Vendas): those plans do not hold conversations, so every message
 * says it is only an alert and points the customer to the store's own contact. Text follows the customer's language.
 */
public final class AlertFooter {
    /** WhatsApp template footers are limited to 60 characters and cannot contain variables. */
    public static final int WHATSAPP_MAX = 60;

    private AlertFooter() {}

    public static String text(String locale, String contact) {
        String c = contact == null || contact.isBlank() ? null : contact.trim();
        return switch (language(locale)) {
            case "pt" -> c == null ? "Esta mensagem é apenas um alerta." : "Esta mensagem é apenas um alerta. Para falar com a loja: " + c;
            case "es" -> c == null ? "Este mensaje es solo un aviso." : "Este mensaje es solo un aviso. Para hablar con la tienda: " + c;
            case "fr" -> c == null ? "Ce message est uniquement une alerte." : "Ce message est uniquement une alerte. Pour contacter la boutique : " + c;
            case "it" -> c == null ? "Questo messaggio è solo un avviso." : "Questo messaggio è solo un avviso. Per parlare con il negozio: " + c;
            case "de" -> c == null ? "Diese Nachricht ist nur ein Hinweis." : "Diese Nachricht ist nur ein Hinweis. Kontakt zum Geschäft: " + c;
            default -> c == null ? "This message is only an alert." : "This message is only an alert. To talk to the store: " + c;
        };
    }

    /** Short form for WhatsApp template footers (≤ 60 characters); drops the contact if it does not fit. */
    public static String whatsapp(String locale, String contact) {
        String c = contact == null || contact.isBlank() ? null : contact.trim();
        String withContact = switch (language(locale)) {
            case "pt" -> "Apenas um alerta. Fale com a loja: " + c;
            case "es" -> "Solo un aviso. Habla con la tienda: " + c;
            case "fr" -> "Simple alerte. Contactez la boutique : " + c;
            case "it" -> "Solo un avviso. Contatta il negozio: " + c;
            case "de" -> "Nur ein Hinweis. Kontakt: " + c;
            default -> "Only an alert. Contact the store: " + c;
        };
        if (c != null && withContact.length() <= WHATSAPP_MAX) return withContact;
        return switch (language(locale)) {
            case "pt" -> "Esta mensagem é apenas um alerta.";
            case "es" -> "Este mensaje es solo un aviso.";
            case "fr" -> "Ce message est uniquement une alerte.";
            case "it" -> "Questo messaggio è solo un avviso.";
            case "de" -> "Diese Nachricht ist nur ein Hinweis.";
            default -> "This message is only an alert.";
        };
    }

    static String language(String locale) {
        if (locale == null || locale.isBlank()) return "en";
        return locale.replace('_', '-').split("-")[0].toLowerCase(Locale.ROOT);
    }
}
