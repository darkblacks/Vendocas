package com.platform.shared.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The platform's languages and the single country → language rule. Six languages, no regional variants
 * (Brazil and Portugal are both "pt", Canada and the US are both "en", Spain and Latin America are both "es"):
 * <ul>
 *   <li>pt — Brazil, Portugal</li>
 *   <li>es — Spain and every Spanish-speaking country of Latin America</li>
 *   <li>fr — France (and French Guiana, Monaco)</li>
 *   <li>de — Germany, Austria</li>
 *   <li>it — Italy</li>
 *   <li>en — United States, Canada, United Kingdom, Ireland and every other country</li>
 * </ul>
 * A language the person chose (a contact's or user's locale) wins over the country; anything outside the six becomes
 * the country's language, then English.
 */
public final class Languages {
    public static final List<String> SUPPORTED = List.of("pt", "en", "es", "fr", "de", "it");
    public static final String FALLBACK = "en";

    private static final Map<String, String> BY_COUNTRY = Map.ofEntries(
            Map.entry("BR", "pt"), Map.entry("PT", "pt"),
            Map.entry("ES", "es"), Map.entry("MX", "es"), Map.entry("GT", "es"), Map.entry("SV", "es"), Map.entry("HN", "es"),
            Map.entry("NI", "es"), Map.entry("CR", "es"), Map.entry("PA", "es"), Map.entry("CU", "es"), Map.entry("DO", "es"),
            Map.entry("PR", "es"), Map.entry("CO", "es"), Map.entry("VE", "es"), Map.entry("EC", "es"), Map.entry("PE", "es"),
            Map.entry("BO", "es"), Map.entry("CL", "es"), Map.entry("AR", "es"), Map.entry("UY", "es"), Map.entry("PY", "es"),
            Map.entry("FR", "fr"), Map.entry("GF", "fr"), Map.entry("MC", "fr"),
            Map.entry("DE", "de"), Map.entry("AT", "de"),
            Map.entry("IT", "it"));

    private static final Set<String> SET = Set.copyOf(SUPPORTED);

    private Languages() {}

    /** Language of a country (ISO 3166 alpha-2); English when the country is unknown or not mapped. */
    public static String forCountry(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return FALLBACK;
        return BY_COUNTRY.getOrDefault(countryCode.trim().toUpperCase(Locale.ROOT), FALLBACK);
    }

    /** "pt-BR" → "pt", "fr_CA" → "fr"; null when it is not one of the six. */
    public static String of(String locale) {
        if (locale == null || locale.isBlank()) return null;
        String l = locale.trim().replace('_', '-').split("-")[0].toLowerCase(Locale.ROOT);
        return SET.contains(l) ? l : null;
    }

    public static boolean isSupported(String locale) {
        return of(locale) != null;
    }

    /** First supported language among the candidates (locales), else the country's language. */
    public static String resolve(String countryCode, String... locales) {
        for (String l : locales) {
            String x = of(l);
            if (x != null) return x;
        }
        return forCountry(countryCode);
    }

    /** WhatsApp (Meta) template language code: Meta has no plain "pt", so Portuguese templates use pt_BR. */
    public static String whatsappCode(String locale) {
        String l = of(locale);
        if (l == null) return locale == null ? "en" : locale.replace('-', '_');
        return "pt".equals(l) ? "pt_BR" : l;
    }
}
