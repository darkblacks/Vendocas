package com.platform.messaging;

/** SMS segment count (GSM-7: 160/153 chars, otherwise UCS-2: 70/67) — drives cost estimation. */
public final class SmsSegments {
    private static final String GSM = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final String GSM_EXT = "^{}\\[~]|€";

    private SmsSegments() {}

    public static int count(String text) {
        if (text == null || text.isEmpty()) return 1;
        boolean gsm = true;
        int units = 0;
        for (char c : text.toCharArray()) {
            if (GSM.indexOf(c) >= 0) units++;
            else if (GSM_EXT.indexOf(c) >= 0) units += 2;
            else { gsm = false; break; }
        }
        if (gsm) return units <= 160 ? 1 : (int) Math.ceil(units / 153.0);
        int len = text.codePointCount(0, text.length());
        return len <= 70 ? 1 : (int) Math.ceil(len / 67.0);
    }
}
