package com.platform.automation.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable step-graph document stored per automation version. Sector-agnostic: the owner composes
 * WHEN (trigger) → WAIT → CHECK (conditions) → DO (actions) freely.
 */
public record AutomationDefinition(Trigger trigger, List<Step> steps, Settings settings) {

    public record Period(int amount, TimeUnitKind unit) {}

    /** Trigger. Only the fields relevant to {@code type} are used. */
    public record Trigger(TriggerType type,
                          String eventType,          // EXTERNAL_EVENT: custom event name
                          Period period,             // CONTACT_INACTIVE: inactivity length
                          String basis,              // CONTACT_INACTIVE: LAST_PURCHASE | LAST_INTERACTION | LAST_VISIT
                          Integer leadMinutes,       // APPOINTMENT_UPCOMING: minutes before start
                          String dateField,          // DATE_FIELD: custom field key holding a DATE
                          Schedule schedule,         // RECURRING
                          Audience audience,         // optional filter on who may enter
                          String reentry) {}         // ONCE | ONCE_PER_EVENT (default) | ONCE_PER_DAY

    public record Schedule(String recurrence, String time, List<Integer> daysOfWeek, Integer dayOfMonth) {}

    public record Audience(List<String> tagsAny, List<String> tagsNone, List<String> countries) {}

    public record Condition(String type, String channel, String purpose, String tag, Integer days, List<String> countries,
                            Long amountMinor, String eventType, String from, String to) {}

    public record Step(String id, StepType type,
                       Period duration,             // WAIT
                       String time, Boolean businessDaysOnly, String zone, // WAIT_UNTIL (zone: ORGANIZATION | CONTACT)
                       List<Condition> conditions, String match, String onTrue, String onFalse, // CONDITION
                       String channel, String purpose, UUID templateId, String subject, String body, UUID mediaAssetId, // SEND
                       String tag, String title, Integer dueInHours, String message, String status, String url, Long valueMinor, // actions
                       String next,
                       Map<String, Object> ui) {}   // editor-only layout data (positions); ignored by the engine

    public record Settings(List<String> exitOn, Integer lateToleranceHours, String onSendBlocked, Long monthlyBudgetMinor,
                           Integer maxAttemptsPerStep, String migrationPolicy, String timeZone) {
        public int lateTolerance() { return lateToleranceHours == null ? 24 : lateToleranceHours; }
        public boolean continueOnBlocked() { return "CONTINUE".equals(onSendBlocked); }
        public int maxAttempts() { return maxAttemptsPerStep == null ? 5 : maxAttemptsPerStep; }
        public List<String> exits() { return exitOn == null ? List.of() : exitOn; }
    }
}
