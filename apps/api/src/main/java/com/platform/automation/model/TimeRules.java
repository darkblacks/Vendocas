package com.platform.automation.model;

import java.time.*;

/**
 * Calendar arithmetic with explicit rules (documented in docs/business-rules.md):
 * <ul>
 *   <li>MINUTES/HOURS are absolute durations (a 2-hour wait across a DST change is 120 real minutes).</li>
 *   <li>DAYS/WEEKS keep the local wall-clock time in the zone (a 1-day wait from 10:00 ends at 10:00).</li>
 *   <li>CALENDAR_MONTHS uses calendar months, never 30/90 days. If the target day does not exist the
 *       last day of the target month is used (Jan 31 + 1 month = Feb 28/29).</li>
 *   <li>YEARS: Feb 29 + 1 year = Feb 28.</li>
 *   <li>BUSINESS_DAYS skip Saturday and Sunday (public holidays are not considered).</li>
 *   <li>A local time inside a DST gap is moved forward by the gap length; in an overlap the earlier
 *       offset is used (java.time ZonedDateTime.of semantics).</li>
 * </ul>
 */
public final class TimeRules {
    private TimeRules() {}

    public static Instant add(Instant from, AutomationDefinition.Period p, ZoneId zone) {
        ZonedDateTime z = from.atZone(zone);
        return switch (p.unit()) {
            case MINUTES -> from.plus(Duration.ofMinutes(p.amount()));
            case HOURS -> from.plus(Duration.ofHours(p.amount()));
            case DAYS -> z.plusDays(p.amount()).toInstant();
            case WEEKS -> z.plusWeeks(p.amount()).toInstant();
            case CALENDAR_MONTHS -> z.plusMonths(p.amount()).toInstant();
            case YEARS -> z.plusYears(p.amount()).toInstant();
            case BUSINESS_DAYS -> addBusinessDays(z, p.amount()).toInstant();
        };
    }

    public static Instant subtract(Instant from, AutomationDefinition.Period p, ZoneId zone) {
        ZonedDateTime z = from.atZone(zone);
        return switch (p.unit()) {
            case MINUTES -> from.minus(Duration.ofMinutes(p.amount()));
            case HOURS -> from.minus(Duration.ofHours(p.amount()));
            case DAYS -> z.minusDays(p.amount()).toInstant();
            case WEEKS -> z.minusWeeks(p.amount()).toInstant();
            case CALENDAR_MONTHS -> z.minusMonths(p.amount()).toInstant();
            case YEARS -> z.minusYears(p.amount()).toInstant();
            case BUSINESS_DAYS -> addBusinessDays(z, -p.amount()).toInstant();
        };
    }

    static ZonedDateTime addBusinessDays(ZonedDateTime z, int n) {
        int step = n >= 0 ? 1 : -1;
        int remaining = Math.abs(n);
        ZonedDateTime cur = z;
        while (remaining > 0) {
            cur = cur.plusDays(step);
            if (cur.getDayOfWeek() != DayOfWeek.SATURDAY && cur.getDayOfWeek() != DayOfWeek.SUNDAY) remaining--;
        }
        return cur;
    }

    /** Next instant >= {@code from} at local {@code time} in {@code zone}, optionally on business days only. */
    public static Instant nextLocalTime(Instant from, LocalTime time, ZoneId zone, boolean businessDaysOnly) {
        LocalDate day = from.atZone(zone).toLocalDate();
        for (int i = 0; i < 8; i++) {
            LocalDate d = day.plusDays(i);
            if (businessDaysOnly && (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)) continue;
            Instant candidate = ZonedDateTime.of(d, time, zone).toInstant();
            if (!candidate.isBefore(from)) return candidate;
        }
        throw new IllegalStateException("unreachable");
    }

    /** Next occurrence for a RECURRING schedule strictly after {@code after}. daysOfWeek: 1=Mon..7=Sun. */
    public static Instant nextOccurrence(Instant after, AutomationDefinition.Schedule s, ZoneId zone) {
        LocalTime t = LocalTime.parse(s.time());
        LocalDate start = after.atZone(zone).toLocalDate();
        for (int i = 0; i <= 62; i++) {
            LocalDate d = start.plusDays(i);
            boolean ok = switch (s.recurrence()) {
                case "DAILY" -> true;
                case "WEEKLY" -> s.daysOfWeek() != null && s.daysOfWeek().contains(d.getDayOfWeek().getValue());
                case "MONTHLY" -> {
                    int dom = s.dayOfMonth() == null ? 1 : s.dayOfMonth();
                    yield d.getDayOfMonth() == Math.min(dom, d.lengthOfMonth()); // day 31 → last day of shorter months
                }
                default -> false;
            };
            if (!ok) continue;
            Instant candidate = ZonedDateTime.of(d, t, zone).toInstant();
            if (candidate.isAfter(after)) return candidate;
        }
        throw new IllegalArgumentException("Schedule never fires");
    }
}
