package com.platform.contacts;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.Optional;

public final class Phones {
    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

    private Phones() {}

    /** Parses a user-entered phone into E.164 using {@code defaultRegion} for national formats. */
    public static Optional<String> toE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        try {
            Phonenumber.PhoneNumber n = UTIL.parse(raw.trim(), defaultRegion == null ? "ZZ" : defaultRegion);
            if (!UTIL.isValidNumber(n)) return Optional.empty();
            return Optional.of(UTIL.format(n, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    /** ISO country of an E.164 number (used as the recipient country for SMS/WhatsApp policy). */
    public static Optional<String> region(String e164) {
        if (e164 == null) return Optional.empty();
        try {
            String r = UTIL.getRegionCodeForNumber(UTIL.parse(e164, "ZZ"));
            return r == null || "ZZ".equals(r) ? Optional.empty() : Optional.of(r);
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    public static boolean isMobileCapable(String e164) {
        try {
            var type = UTIL.getNumberType(UTIL.parse(e164, "ZZ"));
            return type == PhoneNumberUtil.PhoneNumberType.MOBILE || type == PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE;
        } catch (NumberParseException e) {
            return false;
        }
    }
}
