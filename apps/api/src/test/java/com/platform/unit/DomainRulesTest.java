package com.platform.unit;

import com.platform.automation.model.AutomationDefinition;
import com.platform.automation.model.AutomationDefinition.Period;
import com.platform.automation.model.AutomationDefinition.Schedule;
import com.platform.automation.model.TimeRules;
import com.platform.automation.model.TimeUnitKind;
import com.platform.billing.SubscriptionStatus;
import com.platform.messaging.QuietHours;
import com.platform.messaging.SmsSegments;
import com.platform.messaging.TwilioSignature;
import com.platform.tenants.TaxIdValidator;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DomainRulesTest {
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final ZoneId NY = ZoneId.of("America/New_York");
    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Test
    void threeCalendarMonthsAreNotNinetyDays() {
        Instant start = ZonedDateTime.of(2026, 1, 15, 10, 0, 0, 0, SP).toInstant();
        Instant plus3m = TimeRules.add(start, new Period(3, TimeUnitKind.CALENDAR_MONTHS), SP);
        assertThat(plus3m.atZone(SP).toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(Duration.between(start, plus3m).toDays()).isEqualTo(90); // Jan15→Apr15 = 90 days here...
        Instant fromMarch = ZonedDateTime.of(2026, 3, 15, 10, 0, 0, 0, SP).toInstant();
        Instant m = TimeRules.add(fromMarch, new Period(3, TimeUnitKind.CALENDAR_MONTHS), SP);
        assertThat(Duration.between(fromMarch, m).toDays()).isEqualTo(92); // ...but Mar15→Jun15 = 92 days
    }

    @Test
    void monthEndClampsToLastDayOfTargetMonth() {
        Instant jan31 = ZonedDateTime.of(2026, 1, 31, 9, 0, 0, 0, SP).toInstant();
        assertThat(TimeRules.add(jan31, new Period(1, TimeUnitKind.CALENDAR_MONTHS), SP).atZone(SP).toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        Instant leap = ZonedDateTime.of(2028, 2, 29, 9, 0, 0, 0, SP).toInstant();
        assertThat(TimeRules.add(leap, new Period(1, TimeUnitKind.YEARS), SP).atZone(SP).toLocalDate()).isEqualTo(LocalDate.of(2029, 2, 28));
    }

    @Test
    void daysKeepWallClockAcrossDstWhileHoursAreAbsolute() {
        // US DST starts 2026-03-08 02:00 in New York.
        Instant before = ZonedDateTime.of(2026, 3, 7, 10, 0, 0, 0, NY).toInstant();
        Instant oneDay = TimeRules.add(before, new Period(1, TimeUnitKind.DAYS), NY);
        assertThat(oneDay.atZone(NY).toLocalTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(Duration.between(before, oneDay).toHours()).isEqualTo(23);
        Instant h24 = TimeRules.add(before, new Period(24, TimeUnitKind.HOURS), NY);
        assertThat(h24.atZone(NY).toLocalTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void localTimeInsideDstGapMovesForward() {
        // Europe/Berlin springs forward 2026-03-29 02:00 → 03:00; 02:30 does not exist.
        Instant from = ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, BERLIN).toInstant();
        Instant at = TimeRules.nextLocalTime(from, LocalTime.of(2, 30), BERLIN, false);
        assertThat(at.atZone(BERLIN).toLocalTime()).isEqualTo(LocalTime.of(3, 30));
    }

    @Test
    void businessDaysSkipWeekends() {
        Instant friday = ZonedDateTime.of(2026, 11, 20, 9, 0, 0, 0, SP).toInstant();
        assertThat(TimeRules.add(friday, new Period(1, TimeUnitKind.BUSINESS_DAYS), SP).atZone(SP).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        Instant sat = ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, SP).toInstant();
        assertThat(TimeRules.nextLocalTime(sat, LocalTime.of(9, 0), SP, true).atZone(SP).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    void monthlyRecurrenceOnDay31UsesLastDayOfShortMonths() {
        Instant afterJan31 = ZonedDateTime.of(2026, 1, 31, 12, 0, 0, 0, SP).toInstant();
        Instant next = TimeRules.nextOccurrence(afterJan31, new Schedule("MONTHLY", "09:00", null, 31), SP);
        assertThat(next.atZone(SP).toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void quietHoursCrossingMidnight() {
        LocalTime start = LocalTime.of(21, 0), end = LocalTime.of(8, 0);
        assertThat(QuietHours.inQuiet(LocalTime.of(23, 0), start, end)).isTrue();
        assertThat(QuietHours.inQuiet(LocalTime.of(7, 59), start, end)).isTrue();
        assertThat(QuietHours.inQuiet(LocalTime.of(8, 0), start, end)).isFalse();
        Instant lateNight = ZonedDateTime.of(2026, 11, 19, 23, 30, 0, 0, SP).toInstant();
        assertThat(QuietHours.nextAllowed(lateNight, SP, start, end).atZone(SP)).isEqualTo(ZonedDateTime.of(2026, 11, 20, 8, 0, 0, 0, SP));
    }

    @Test
    void taxIdentifiers() {
        assertThat(TaxIdValidator.isValid("BR_CNPJ", "BR", "11.222.333/0001-81")).isTrue();
        assertThat(TaxIdValidator.isValid("BR_CNPJ", "BR", "11.222.333/0001-82")).isFalse();
        assertThat(TaxIdValidator.isValid("BR_CNPJ", "BR", "00000000000000")).isFalse();
        // Alphanumeric CNPJ example published by Receita Federal: 12.ABC.345/01DE-35
        assertThat(TaxIdValidator.isValid("BR_CNPJ", "BR", "12.ABC.345/01DE-35")).isTrue();
        assertThat(TaxIdValidator.isValid("BR_CPF", "BR", "529.982.247-25")).isTrue();
        assertThat(TaxIdValidator.isValid("BR_CPF", "BR", "111.111.111-11")).isFalse();
        assertThat(TaxIdValidator.isValid("US_EIN", "US", "12-3456789")).isTrue();
        assertThat(TaxIdValidator.isValid("EU_VAT", "DE", "DE123456789")).isTrue();
        assertThat(TaxIdValidator.isValid("EU_VAT", "FR", "FRXX123456789")).isTrue();
        assertThat(TaxIdValidator.isValid("EU_VAT", "GR", "EL123456789")).isTrue();
        assertThat(TaxIdValidator.isValid("EU_VAT", "PT", "PT12345678")).isFalse();
        assertThat(TaxIdValidator.typeAllowedFor("BR", "US_EIN")).isFalse();
    }

    @Test
    void smsSegments() {
        assertThat(SmsSegments.count("a".repeat(160))).isEqualTo(1);
        assertThat(SmsSegments.count("a".repeat(161))).isEqualTo(2);
        assertThat(SmsSegments.count("Olá, promoção é hoje! 🍕")).isEqualTo(1);
        assertThat(SmsSegments.count("ç".repeat(70) + "🍕")).isEqualTo(2);
    }

    @Test
    void twilioSignatureDependsOnUrlAndParams() {
        var params = Map.of("To", "+15005550006", "From", "+15551234567", "Body", "hi");
        String sig = TwilioSignature.compute("token", "https://api.example.com/hook", params);
        assertThat(TwilioSignature.valid("token", "https://api.example.com/hook", params, sig)).isTrue();
        assertThat(TwilioSignature.valid("token", "https://api.example.com/hook", Map.of("To", "+1"), sig)).isFalse();
        assertThat(TwilioSignature.valid("other", "https://api.example.com/hook", params, sig)).isFalse();
    }

    @Test
    void subscriptionStateMachine() {
        assertThat(SubscriptionStatus.CHECKOUT_PENDING.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.PAST_DUE)).isTrue();
        assertThat(SubscriptionStatus.PAST_DUE.canTransitionTo(SubscriptionStatus.GRACE_PERIOD)).isTrue();
        assertThat(SubscriptionStatus.CANCELED.canTransitionTo(SubscriptionStatus.ACTIVE)).isFalse();
        assertThat(SubscriptionStatus.DRAFT.canTransitionTo(SubscriptionStatus.ACTIVE)).isFalse();
        assertThat(SubscriptionStatus.SUSPENDED.entitlesService()).isFalse();
        assertThat(SubscriptionStatus.GRACE_PERIOD.entitlesService()).isTrue();
    }

    @Test
    void validatorRejectsLoopsAndMissingRefs() {
        var send = new AutomationDefinition.Step("s2", com.platform.automation.model.StepType.SEND, null, null, null, null, null, null, null, null,
                "EMAIL", "REACTIVATION", null, "Hi", "Body", null, null, null, null, null, null, null, null, "s1", null);
        var wait = new AutomationDefinition.Step("s1", com.platform.automation.model.StepType.WAIT, new Period(1, TimeUnitKind.DAYS), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        var def = new AutomationDefinition(new AutomationDefinition.Trigger(com.platform.automation.model.TriggerType.CONTACT_CREATED, null, null, null,
                null, null, null, null, null), List.of(wait, send), null);
        assertThat(com.platform.automation.model.DefinitionValidator.validate(def)).contains("The flow contains a loop");
    }
}
