package com.platform.automation.model;

import com.platform.shared.api.ApiException;
import com.platform.shared.api.ErrorCode;

import java.time.LocalTime;
import java.util.*;

/** Structural validation of an automation graph before it can be published. */
public final class DefinitionValidator {
    static final Set<String> CONDITIONS = Set.of("HAS_EMAIL", "HAS_PHONE", "CHANNEL_CONNECTED", "CHANNEL_IN_PLAN", "CONSENT", "NO_PURCHASE_SINCE_TRIGGER",
            "NO_REPLY_SINCE_TRIGGER", "NOT_MESSAGED_RECENTLY", "NOT_BLOCKED", "COUNTRY_IN", "HAS_TAG", "NOT_HAS_TAG", "LIFETIME_VALUE_GTE",
            "HAS_EVENT", "WITHIN_HOURS", "BUDGET_AVAILABLE");
    static final Set<String> PURPOSES = Set.of("TRANSACTIONAL", "SERVICE_REMINDER", "CUSTOMER_CARE", "MARKETING", "REACTIVATION");

    private DefinitionValidator() {}

    public static List<String> validate(AutomationDefinition d) {
        List<String> errors = new ArrayList<>();
        if (d == null || d.trigger() == null || d.trigger().type() == null) {
            errors.add("A trigger is required");
            return errors;
        }
        var t = d.trigger();
        switch (t.type()) {
            case CONTACT_INACTIVE -> {
                if (t.period() == null || t.period().amount() <= 0) errors.add("Inactivity period is required");
                if (t.basis() != null && !Set.of("LAST_PURCHASE", "LAST_INTERACTION", "LAST_VISIT").contains(t.basis())) errors.add("Invalid inactivity basis");
            }
            case STAGE_CHANGED -> {
                if (t.eventType() != null && !com.platform.crm.CrmService.STAGES.contains(t.eventType())) errors.add("Unknown customer stage: " + t.eventType());
            }
            case EXTERNAL_EVENT -> { if (t.eventType() == null || !t.eventType().matches("^[A-Za-z0-9_.:-]{1,60}$")) errors.add("External event name is required"); }
            case APPOINTMENT_UPCOMING -> { if (t.leadMinutes() == null || t.leadMinutes() < 15) errors.add("Lead time must be at least 15 minutes"); }
            case DATE_FIELD -> { if (t.dateField() == null) errors.add("Date field is required"); }
            case RECURRING -> {
                var s = t.schedule();
                if (s == null || s.recurrence() == null || s.time() == null) errors.add("Recurring schedule needs recurrence and time");
                else {
                    try { LocalTime.parse(s.time()); } catch (Exception e) { errors.add("Invalid time"); }
                    if ("WEEKLY".equals(s.recurrence()) && (s.daysOfWeek() == null || s.daysOfWeek().isEmpty())) errors.add("Pick at least one weekday");
                }
            }
            default -> { }
        }
        if (d.steps() == null || d.steps().isEmpty()) {
            errors.add("Add at least one step");
            return errors;
        }
        Map<String, AutomationDefinition.Step> byId = new HashMap<>();
        for (var s : d.steps()) {
            if (s.id() == null || !s.id().matches("^[A-Za-z0-9_-]{1,64}$")) { errors.add("Every step needs a valid id"); continue; }
            if (byId.put(s.id(), s) != null) errors.add("Duplicate step id " + s.id());
            if (s.type() == null) { errors.add("Step " + s.id() + " has no type"); continue; }
            switch (s.type()) {
                case WAIT -> { if (s.duration() == null || s.duration().amount() <= 0 || s.duration().unit() == null) errors.add("Step " + s.id() + ": wait duration required"); }
                case WAIT_UNTIL -> {
                    try { LocalTime.parse(s.time()); } catch (Exception e) { errors.add("Step " + s.id() + ": invalid time"); }
                }
                case CONDITION -> {
                    if (s.conditions() == null || s.conditions().isEmpty()) errors.add("Step " + s.id() + ": add at least one condition");
                    else for (var c : s.conditions()) if (!CONDITIONS.contains(c.type())) errors.add("Step " + s.id() + ": unknown condition " + c.type());
                }
                case SEND -> {
                    if (s.channel() == null || !Set.of("EMAIL", "WHATSAPP", "SMS").contains(s.channel())) errors.add("Step " + s.id() + ": choose a channel");
                    if (!PURPOSES.contains(s.purpose())) errors.add("Step " + s.id() + ": choose the message purpose");
                    if (s.templateId() == null && (s.body() == null || s.body().isBlank())) errors.add("Step " + s.id() + ": message is empty");
                    if ("EMAIL".equals(s.channel()) && s.templateId() == null && (s.subject() == null || s.subject().isBlank())) errors.add("Step " + s.id() + ": email subject required");
                    if ("WHATSAPP".equals(s.channel()) && s.templateId() == null && t.type() != TriggerType.REPLY_RECEIVED) {
                        errors.add("Step " + s.id() + ": WhatsApp messages started by the business require an approved template");
                    }
                }
                case ADD_TAG, REMOVE_TAG -> { if (s.tag() == null || s.tag().isBlank()) errors.add("Step " + s.id() + ": tag required"); }
                case CREATE_TASK, CREATE_OPPORTUNITY -> { if (s.title() == null || s.title().isBlank()) errors.add("Step " + s.id() + ": title required"); }
                case NOTIFY_OPERATOR -> { if (s.message() == null || s.message().isBlank()) errors.add("Step " + s.id() + ": message required"); }
                case SET_CONTACT_STATUS -> { if (!Set.of("ACTIVE", "BLOCKED").contains(s.status())) errors.add("Step " + s.id() + ": status must be ACTIVE or BLOCKED"); }
                case CALL_WEBHOOK -> { if (s.url() == null || !s.url().startsWith("https://")) errors.add("Step " + s.id() + ": webhook URL must use https"); }
                default -> { }
            }
        }
        for (var s : d.steps()) {
            for (String ref : Arrays.asList(s.next(), s.onTrue(), s.onFalse())) {
                if (ref != null && !"end".equals(ref) && !byId.containsKey(ref)) errors.add("Step " + s.id() + " points to missing step " + ref);
            }
        }
        if (errors.isEmpty() && hasCycle(d, d.steps().getFirst().id(), byId, new HashSet<>(), new HashSet<>())) errors.add("The flow contains a loop");
        return errors;
    }

    public static void require(AutomationDefinition d) {
        List<String> errors = validate(d);
        if (!errors.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "The automation is not valid", Map.of("errors", errors));
    }

    private static boolean hasCycle(AutomationDefinition d, String id, Map<String, AutomationDefinition.Step> byId, Set<String> stack, Set<String> done) {
        if (id == null || "end".equals(id) || done.contains(id)) return false;
        if (!stack.add(id)) return true;
        var s = byId.get(id);
        for (String n : Arrays.asList(nextOf(d, s), s.onTrue(), s.onFalse())) if (hasCycle(d, n, byId, stack, done)) return true;
        stack.remove(id);
        done.add(id);
        return false;
    }

    /** Implicit sequencing: a step without explicit next continues with the following step in the list. */
    public static String nextOf(AutomationDefinition d, AutomationDefinition.Step s) {
        if (s.next() != null) return "end".equals(s.next()) ? null : s.next();
        if (s.type() == StepType.CONDITION || s.type() == StepType.END) return null;
        int idx = d.steps().indexOf(s);
        return idx + 1 < d.steps().size() ? d.steps().get(idx + 1).id() : null;
    }
}
