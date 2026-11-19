package com.platform.messaging;

import java.time.*;

/**
 * Quiet-hours deferral in a given IANA zone. Window may cross midnight (e.g. 21:00–08:00).
 * DST gaps resolve forward (ZonedDateTime.of semantics), so 08:00 on a spring-forward day still
 * maps to a valid instant.
 */
public final class QuietHours {
    private QuietHours() {}

    public static boolean inQuiet(LocalTime t, LocalTime start, LocalTime end) {
        if (start.equals(end)) return false;
        return start.isBefore(end) ? !t.isBefore(start) && t.isBefore(end) : !t.isBefore(start) || t.isBefore(end);
    }

    public static Instant nextAllowed(Instant now, ZoneId zone, LocalTime start, LocalTime end) {
        ZonedDateTime local = now.atZone(zone);
        if (!inQuiet(local.toLocalTime(), start, end)) return now;
        LocalDate day = local.toLocalTime().isBefore(end) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        return ZonedDateTime.of(day, end, zone).toInstant();
    }
}
