package com.platform.automation.model;

/** How a contact enters an automation. EVENT triggers react to outbox events; SWEEP/SCHEDULE triggers are evaluated by durable jobs. */
public enum TriggerType {
    CONTACT_CREATED(Kind.EVENT, "contact.created"),
    CONTACT_UPDATED(Kind.EVENT, "contact.updated"),
    PURCHASE_COMPLETED(Kind.EVENT, "PURCHASE_COMPLETED"),
    SERVICE_COMPLETED(Kind.EVENT, "SERVICE_COMPLETED"),
    APPOINTMENT_CREATED(Kind.EVENT, "APPOINTMENT_CREATED"),
    APPOINTMENT_CANCELED(Kind.EVENT, "APPOINTMENT_CANCELED"),
    MISSED_CALL(Kind.EVENT, "MISSED_CALL"),
    FORM_SUBMITTED(Kind.EVENT, "FORM_SUBMITTED"),
    CHECKOUT_STARTED(Kind.EVENT, "CHECKOUT_STARTED"),
    CHECKOUT_ABANDONED(Kind.EVENT, "CHECKOUT_ABANDONED"),
    PAGE_ABANDONED(Kind.EVENT, "PAGE_ABANDONED"),
    QUOTE_CREATED(Kind.EVENT, "QUOTE_CREATED"),
    REPLY_RECEIVED(Kind.EVENT, "REPLY_RECEIVED"),
    /** Customer stage changed (CRM). Optional {@code eventType} = target stage, e.g. AT_RISK for "Recompra / feedback". */
    STAGE_CHANGED(Kind.EVENT, "STAGE_CHANGED"),
    EXTERNAL_EVENT(Kind.EVENT, null),
    CONTACT_INACTIVE(Kind.SWEEP, null),
    APPOINTMENT_UPCOMING(Kind.SWEEP, null),
    BIRTHDAY(Kind.SWEEP, null),
    DATE_FIELD(Kind.SWEEP, null),
    RECURRING(Kind.SCHEDULE, null);

    public enum Kind { EVENT, SWEEP, SCHEDULE }

    private final Kind kind;
    private final String eventType;

    TriggerType(Kind kind, String eventType) {
        this.kind = kind;
        this.eventType = eventType;
    }

    public Kind kind() { return kind; }

    /** Contact event type (or outbox event type for contact.created/updated) that fires this trigger. */
    public String eventType() { return eventType; }
}
