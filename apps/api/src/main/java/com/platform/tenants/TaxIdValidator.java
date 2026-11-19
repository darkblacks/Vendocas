package com.platform.tenants;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Format/checksum validation of regional tax identifiers. This is NOT registry verification:
 * EU VAT numbers are additionally checked against VIES ({@link ViesClient}); CNPJ/EIN registry
 * lookups are out of scope and the status stays FORMAT_VALID.
 */
public final class TaxIdValidator {
    private TaxIdValidator() {}

    /** EU VAT number patterns (without the country prefix). Source: EC VIES published formats. */
    static final Map<String, Pattern> VAT = Map.ofEntries(
            Map.entry("AT", Pattern.compile("U\\d{8}")), Map.entry("BE", Pattern.compile("[01]\\d{9}")),
            Map.entry("BG", Pattern.compile("\\d{9,10}")), Map.entry("HR", Pattern.compile("\\d{11}")),
            Map.entry("CY", Pattern.compile("\\d{8}[A-Z]")), Map.entry("CZ", Pattern.compile("\\d{8,10}")),
            Map.entry("DK", Pattern.compile("\\d{8}")), Map.entry("EE", Pattern.compile("\\d{9}")),
            Map.entry("FI", Pattern.compile("\\d{8}")), Map.entry("FR", Pattern.compile("[0-9A-HJ-NP-Z]{2}\\d{9}")),
            Map.entry("DE", Pattern.compile("\\d{9}")), Map.entry("EL", Pattern.compile("\\d{9}")),
            Map.entry("HU", Pattern.compile("\\d{8}")), Map.entry("IE", Pattern.compile("\\d{7}[A-W][A-I]?|\\d[A-Z+*]\\d{5}[A-W]")),
            Map.entry("IT", Pattern.compile("\\d{11}")), Map.entry("LV", Pattern.compile("\\d{11}")),
            Map.entry("LT", Pattern.compile("\\d{9}|\\d{12}")), Map.entry("LU", Pattern.compile("\\d{8}")),
            Map.entry("MT", Pattern.compile("\\d{8}")), Map.entry("NL", Pattern.compile("\\d{9}B\\d{2}")),
            Map.entry("PL", Pattern.compile("\\d{10}")), Map.entry("PT", Pattern.compile("\\d{9}")),
            Map.entry("RO", Pattern.compile("\\d{2,10}")), Map.entry("SK", Pattern.compile("\\d{10}")),
            Map.entry("SI", Pattern.compile("\\d{8}")), Map.entry("ES", Pattern.compile("[0-9A-Z]\\d{7}[0-9A-Z]")),
            Map.entry("SE", Pattern.compile("\\d{12}")));

    public static String normalize(String raw) {
        return raw == null ? null : raw.replaceAll("[\\s./\\-]", "").toUpperCase();
    }

    public static boolean isValid(String type, String countryCode, String raw) {
        String v = normalize(raw);
        if (v == null || v.isEmpty()) return false;
        return switch (type) {
            case "BR_CNPJ" -> cnpj(v);
            case "BR_CPF" -> cpf(v);
            case "US_EIN" -> v.matches("\\d{9}") && !v.startsWith("00");
            case "EU_VAT" -> vat(countryCode, v);
            default -> true;
        };
    }

    /** VAT including or excluding the country prefix (Greece uses "EL"). */
    static boolean vat(String countryCode, String v) {
        String prefix = "GR".equals(countryCode) ? "EL" : countryCode;
        String body = v.startsWith(prefix) ? v.substring(prefix.length()) : v;
        Pattern p = VAT.get(prefix);
        return p != null && p.matcher(body).matches();
    }

    /**
     * CNPJ check digits. Supports the alphanumeric CNPJ (IN RFB 2.229/2024, issued from July 2026):
     * the 12 base positions may be [0-9A-Z]; each character's value is (ASCII code − 48); the two
     * check digits remain numeric and use the same modulo-11 weights.
     */
    static boolean cnpj(String v) {
        if (!v.matches("[0-9A-Z]{12}\\d{2}")) return false;
        if (v.chars().allMatch(c -> c == v.charAt(0))) return false;
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return dv(v, w1, 12) == v.charAt(12) - '0' && dv(v, w2, 13) == v.charAt(13) - '0';
    }

    private static int dv(String v, int[] weights, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) sum += (v.charAt(i) - 48) * weights[i];
        int r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }

    static boolean cpf(String v) {
        if (!v.matches("\\d{11}") || v.chars().distinct().count() == 1) return false;
        for (int j = 9; j <= 10; j++) {
            int sum = 0;
            for (int i = 0; i < j; i++) sum += (v.charAt(i) - '0') * (j + 1 - i);
            int d = (sum * 10) % 11 % 10;
            if (d != v.charAt(j) - '0') return false;
        }
        return true;
    }

    /** Which identifier types are acceptable for a billing country. */
    public static boolean typeAllowedFor(String countryRegion, String type) {
        return switch (countryRegion) {
            case "BR" -> type.equals("BR_CNPJ") || type.equals("BR_CPF");
            case "US" -> type.equals("US_EIN") || type.equals("NONE");
            case "EU" -> type.equals("EU_VAT") || type.equals("NONE") || type.equals("OTHER");
            default -> false;
        };
    }
}
